package dev.lightmap.mc;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import dev.lightmap.LightMapClient;
import dev.lightmap.Settings;
import dev.lightmap.core.Check;
import dev.lightmap.core.MapWorker;
import dev.lightmap.core.Markers;
import dev.lightmap.core.RegionFiles;
import dev.lightmap.core.RegionPage;
import dev.lightmap.core.Regions;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Runs the session, the minimap and the world map screen the way the game would, without the
 * game: a bare client object with just the parts they read (window scale, camera entity, level),
 * real chunks fed through the session's own entry points, and the stand-in GPU from
 * {@link UploadTests}. What the minimap and the screen would hand to the GUI renderer is then read
 * back from the game's real render-state object and checked against independently computed
 * positions.
 */
final class ScreenTests {
    private static final double CAMERA_X = 100.5;
    private static final double CAMERA_Z = -50.25;
    private static final float YAW = 30f;
    private static final int GUI_SCALE = 2;

    private ScreenTests() {
    }

    static void run() throws Exception {
        UploadTests.FakeGpu gpu = UploadTests.gpu();
        Path gameDir = Files.createTempDirectory("lightmap-screen-test");

        // A client with only what the map reads from it.
        Minecraft minecraft = bare(Minecraft.class);
        Window window = bare(Window.class);
        GameTests.set(Window.class, window, "guiScale", GUI_SCALE);
        GameRenderer renderer = bare(GameRenderer.class);
        Camera camera = bare(Camera.class);
        ClientLevel level = bare(ClientLevel.class);
        GameTests.set(Level.class, level, "dimension", Level.OVERWORLD);
        Marker entity = bare(Marker.class);
        GameTests.set(Entity.class, entity, "level", level);
        GameTests.set(Entity.class, entity, "position", new Vec3(CAMERA_X, 80, CAMERA_Z));
        GameTests.set(Entity.class, entity, "yRot", YAW);
        entity.xo = CAMERA_X;
        entity.zo = CAMERA_Z;
        entity.yRotO = YAW;
        GameTests.set(Camera.class, camera, "entity", entity);
        GameTests.set(GameRenderer.class, renderer, "mainCamera", camera);
        GameTests.set(Minecraft.class, minecraft, "window", window);
        GameTests.set(Minecraft.class, minecraft, "gameRenderer", renderer);
        GameTests.set(Minecraft.class, minecraft, "gui", bare(Gui.class));
        GameTests.set(Minecraft.class, minecraft, "gameDirectory", gameDir.toFile());
        GameTests.set(Minecraft.class, null, "instance", minecraft);

        MapWorker worker = new MapWorker("lightmap-screen-test");
        try {
            MapSession session = MapSession.open(minecraft, level, worker);
            GameTests.set(LightMapClient.class, null, "session", session);

            // Chunks arrive through the same call the chunk-load event makes.
            int texturesBefore = gpu.textures.size();
            for (int cz = -24; cz < 24; cz++) {
                for (int cx = -24; cx < 24; cx++) {
                    session.chunkLoaded(GameTests.terrainChunk(cx, cz));
                }
            }
            Check.that(worker.await(60_000), "worker caught up");
            Check.eq(2304L, session.stats.clientChunksCaptured, "chunks captured");
            Check.eq(2304L, session.stats.workerChunksApplied, "chunks applied");
            int ticks = settle(session);
            Check.eq(4, gpu.textures.size() - texturesBefore, "one texture per region");
            Check.that(ticks >= 4, "new pages went out over several ticks, 1 MB each (" + ticks + ")");
            long callsWhenSettled = session.stats.clientUploadCalls;
            for (int i = 0; i < 100; i++) {
                session.tick();
            }
            Check.eq(callsWhenSettled, session.stats.clientUploadCalls, "a hundred idle ticks upload nothing");

            minimap(session);
            worldMap(session);
            List<Markers.Marker> placed = markers(session);

            // Leaving the world frees the textures and saves the map under the game directory.
            session.close();
            GameTests.set(LightMapClient.class, null, "session", null);
            Check.that(worker.await(60_000), "worker finished saving");
            Check.that(gpu.textures.stream().allMatch(t -> t.closed), "textures closed on leaving");
            Path saved = gameDir.resolve("lightmap").resolve("server-unknown").resolve("minecraft_overworld");
            Check.eq(4, new RegionFiles(saved).list().length, "four region files in " + gameDir.relativize(saved));
            Check.eq(placed, Markers.decode(new RegionFiles(saved).readMarkers()), "markers saved next to them");

            // Coming back: nothing is loaded and no chunks arrive, yet the minimap asks for the
            // saved regions around the camera and gets them from disk.
            MapSession again = MapSession.open(minecraft, level, worker);
            GameTests.set(LightMapClient.class, null, "session", again);
            Check.that(worker.await(60_000), "second session opened");
            Check.eq(placed, List.of(again.markers.all()), "markers read back in the next session");
            again.tick();
            MinimapHud hud = new MinimapHud();
            GuiRenderState state = new GuiRenderState();
            hud.submit(state, DeltaTracker.ONE);
            List<GuiElementRenderState> waiting = elements(state);
            Check.eq(7, waiting.size(), "before the regions load: four blank regions, the saved marker with its outline and the arrow");
            for (int i = 0; i < 4; i++) {
                Check.that(waiting.get(i).textureSetup() == TextureSetup.noTexture(), "blank region " + i + " has no texture");
                Check.eq(MinimapHud.BACKDROP, vertices(waiting.get(i)).color, "blank region " + i + " is the unexplored colour");
            }
            Check.that(worker.await(60_000), "regions loaded from disk");
            settle(again);
            state = new GuiRenderState();
            hud.submit(state, DeltaTracker.ONE);
            Check.eq(7, elements(state).size(), "after: four pages, the saved marker with its outline and the arrow");
            Check.that(elements(state).get(0).textureSetup() != TextureSetup.noTexture(), "now textured");
            Check.eq(0L, again.stats.clientChunksCaptured, "without any chunk having loaded");
            Check.eq(4L, again.stats.workerPagesLoaded, "four regions read from disk");
            again.close();
            GameTests.set(LightMapClient.class, null, "session", null);
            Check.that(worker.await(60_000), "second session closed");
        } finally {
            worker.shutdown();
        }
    }

    // ------------------------------------------------------------------ minimap

    private static void minimap(MapSession session) throws Exception {
        MinimapHud hud = new MinimapHud();
        GuiRenderState state = new GuiRenderState();
        hud.submit(state, DeltaTracker.ONE);
        List<GuiElementRenderState> drawn = elements(state);
        Check.eq(5, drawn.size(), "minimap draws four pages and a marker");

        // 368 px square with a 10 px margin, at GUI scale 2.
        ScreenRectangle square = new ScreenRectangle(5, 5, 184, 184);
        float centre = 97f;
        Check.eq(square, drawn.get(0).bounds(), "bounds are the minimap's square");

        // Each page quad must sit where its region belongs, carry that region's texture, and be
        // clipped to the square. Expected positions come straight from the definition: rotate the
        // offset from the camera so that the view direction points up, at half a GUI unit per block.
        double angle = Math.PI - Math.toRadians(YAW);
        Set<Long> matched = new HashSet<>();
        for (int i = 0; i < 4; i++) {
            GuiElementRenderState quad = drawn.get(i);
            Check.eq(square, quad.scissorArea(), "page " + i + " is clipped to the square");
            GameTests.Vertices v = vertices(quad);
            Check.that(v.turn() < 0, "page " + i + " faces the viewer");
            Check.eq(-1, v.color, "page " + i + " is drawn solid at 100% opacity");
            for (int rz = -1; rz <= 0; rz++) {
                for (int rx = -1; rx <= 0; rx++) {
                    double dx = (rx * 512 - CAMERA_X) * 0.5;
                    double dz = (rz * 512 - CAMERA_Z) * 0.5;
                    double sx = centre + dx * Math.cos(angle) - dz * Math.sin(angle);
                    double sy = centre + dx * Math.sin(angle) + dz * Math.cos(angle);
                    if (Math.abs(v.list.get(0)[0] - sx) < 0.01 && Math.abs(v.list.get(0)[1] - sy) < 0.01) {
                        matched.add(Regions.key(rx, rz));
                        GpuPages.Handle handle = (GpuPages.Handle) session.page(Regions.key(rx, rz)).gpu;
                        Check.that(quad.textureSetup() == handle.linear, "region " + rx + "," + rz + " is drawn with its own texture, smoothed");
                        // The opposite corner is one region further east and south.
                        double ex = (rx * 512 + 512 - CAMERA_X) * 0.5;
                        double ez = (rz * 512 + 512 - CAMERA_Z) * 0.5;
                        GameTests.vertex(v.list.get(2),
                                (float) (centre + ex * Math.cos(angle) - ez * Math.sin(angle)),
                                (float) (centre + ex * Math.sin(angle) + ez * Math.cos(angle)), 1f, 1f,
                                "south-east corner of region " + rx + "," + rz);
                    }
                }
            }
        }
        Check.eq(4, matched.size(), "all four regions around the camera are placed correctly");

        GameTests.Vertices marker = vertices(drawn.get(4));
        GameTests.vertex(marker.list.get(0), centre, centre - 4f, Float.NaN, Float.NaN, "marker tip points up from the centre");
        Check.eq(null, drawn.get(4).scissorArea(), "marker is not clipped");

        settings(hud);

        // Nothing is drawn for a camera in some other level.
        Entity camera = Minecraft.getInstance().getCameraEntity();
        GameTests.set(Entity.class, camera, "level", bare(ClientLevel.class));
        GuiRenderState other = new GuiRenderState();
        hud.submit(other, DeltaTracker.ONE);
        Check.eq(0, elements(other).size(), "camera in another level: nothing drawn");
        GameTests.set(Entity.class, camera, "level", session.level);

        // What a frame costs. The render-state object itself allocates its layer lists every
        // frame, with or without the map, so that is measured separately and subtracted.
        int frames = 200_000;
        for (int i = 0; i < 20_000; i++) {
            state.reset();
            hud.submit(state, DeltaTracker.ONE);
        }
        long emptyBytes = allocated();
        for (int i = 0; i < frames; i++) {
            state.reset();
        }
        emptyBytes = allocated() - emptyBytes;
        long bytes = allocated();
        long nanos = System.nanoTime();
        for (int i = 0; i < frames; i++) {
            state.reset();
            hud.submit(state, DeltaTracker.ONE);
        }
        nanos = System.nanoTime() - nanos;
        bytes = allocated() - bytes;
        double perFrame = (bytes - emptyBytes) / (double) frames;
        System.out.printf("  minimap per frame: %.0f ns, %.0f bytes allocated (all of it the GUI's own layer lists)%n",
                nanos / (double) frames, perFrame);
        Check.that(perFrame < 400, "a minimap frame allocates next to nothing (" + Math.round(perFrame) + " bytes)");
    }

    /** The settings take effect on the next frame, without a restart. */
    private static void settings(MinimapHud hud) {
        try {
            Settings.setOpacity(40);
            List<GuiElementRenderState> drawn = draw(hud);
            Check.eq(5, drawn.size(), "40% opacity: same quads");
            for (int i = 0; i < 4; i++) {
                Check.eq(0x66FFFFFF, vertices(drawn.get(i)).color, "40% opacity: page " + i + " has alpha 102 of 255");
            }
            Check.eq(0x66000000 | (MinimapHud.MARKER & 0xFFFFFF), vertices(drawn.get(4)).color, "40% opacity: so has the marker");
            Settings.setOpacity(5);
            Check.eq(0x0DFFFFFF, vertices(draw(hud).get(0)).color, "5% opacity: alpha 13 of 255");
            Settings.setOpacity(0);
            Check.eq(0, draw(hud).size(), "0% opacity: nothing drawn");
            Settings.setOpacity(100);
            Settings.setMinimap(false);
            Check.eq(0, draw(hud).size(), "minimap switched off: nothing drawn");
            Settings.setMinimap(true);

            Settings.setSize(256);
            drawn = draw(hud);
            Check.eq(new ScreenRectangle(5, 5, 128, 128), drawn.get(0).scissorArea(), "size 256 px: a 128-unit square at GUI scale 2");
            GameTests.vertex(vertices(drawn.getLast()).list.get(0), 69f, 65f, Float.NaN, Float.NaN, "and the marker moves to its centre");
            Settings.setSize(368);

            // North up: region (0, 0)'s north-west corner is the world origin, straight from the definition.
            Settings.setRotate(false);
            drawn = draw(hud);
            boolean found = false;
            for (int i = 0; i < 4; i++) {
                float[] corner = vertices(drawn.get(i)).list.get(0);
                found |= Math.abs(corner[0] - (97 - CAMERA_X * 0.5)) < 0.01 && Math.abs(corner[1] - (97 - CAMERA_Z * 0.5)) < 0.01;
            }
            Check.that(found, "rotation off: north is up");
            double heading = Math.toRadians(YAW);
            GameTests.vertex(vertices(drawn.get(4)).list.get(0), (float) (97 - Math.sin(heading) * 4), (float) (97 + Math.cos(heading) * 4),
                    Float.NaN, Float.NaN, "rotation off: the marker turns instead");
        } finally {
            Settings.setOpacity(100);
            Settings.setMinimap(true);
            Settings.setSize(368);
            Settings.setRotate(true);
        }
    }

    private static List<GuiElementRenderState> draw(MinimapHud hud) {
        GuiRenderState state = new GuiRenderState();
        hud.submit(state, DeltaTracker.ONE);
        return elements(state);
    }

    // ------------------------------------------------------------------ world map

    private static void worldMap(MapSession session) throws Exception {
        GameTests.set(LightMapClient.class, null, "openMap",
                new KeyMapping("key.lightmap.world_map", InputConstants.KEY_M, KeyMapping.Category.register(Identifier.fromNamespaceAndPath("lightmap", "selftest"))));

        WorldMapScreen screen = new WorldMapScreen();
        screen.width = 640;
        screen.height = 360;
        screen.init();

        // Opens centred on the camera at one screen pixel per block, north up.
        GuiRenderState state = new GuiRenderState();
        screen.submit(state, 1f);
        List<GuiElementRenderState> drawn = elements(state);
        Check.eq(6, drawn.size(), "world map draws a background, four pages and a marker");
        GameTests.Vertices background = vertices(drawn.get(0));
        GameTests.vertex(background.list.get(2), 640, 360, Float.NaN, Float.NaN, "background covers the screen");
        Check.that(placed(drawn, session, 320, 180, CAMERA_X, CAMERA_Z, 0.5, false), "pages placed around the camera, sharp");
        double heading = Math.toRadians(YAW);
        GameTests.vertex(vertices(drawn.get(5)).list.get(0), (float) (320 - Math.sin(heading) * 5), (float) (180 + Math.cos(heading) * 5),
                Float.NaN, Float.NaN, "marker at the camera, pointing the way it faces");

        // Scrolling in doubles the scale and keeps the block under the cursor in place.
        double underX = CAMERA_X + (400 - 320) / 0.5;
        double underZ = CAMERA_Z + (100 - 180) / 0.5;
        Check.that(screen.mouseScrolled(400, 100, 0, 1), "scroll handled");
        drawn = draw(screen);
        Check.that(placed(drawn, session, 400, 100, underX, underZ, 1.0, false), "zoomed in about the cursor");

        // A drag only counts after a press on this screen. The game reports "a button is held"
        // for a press that happened before the map was opened and whose release it never saw;
        // that must not move the map.
        Check.that(screen.mouseDragged(click(450, 140, InputConstants.MOUSE_BUTTON_LEFT), 50, 40), "stale drag is taken");
        Check.that(placed(draw(screen), session, 400, 100, underX, underZ, 1.0, false), "but a drag without a press here moves nothing");

        // Press, move, release: the map follows the pointer.
        Check.that(screen.mouseClicked(click(400, 100, InputConstants.MOUSE_BUTTON_LEFT), false), "press handled");
        Check.that(screen.mouseDragged(click(410, 96, InputConstants.MOUSE_BUTTON_LEFT), 10, -4), "drag handled");
        drawn = draw(screen);
        Check.that(placed(drawn, session, 410, 96, underX, underZ, 1.0, false), "dragged with the pointer");
        Check.that(screen.mouseReleased(click(410, 96, InputConstants.MOUSE_BUTTON_LEFT)), "release handled");
        screen.mouseDragged(click(440, 126, InputConstants.MOUSE_BUTTON_LEFT), 30, 30);
        Check.that(placed(draw(screen), session, 410, 96, underX, underZ, 1.0, false), "after the release the map stays put");

        // The middle button drags too; the right button does not, it is for markers.
        screen.mouseClicked(click(410, 96, InputConstants.MOUSE_BUTTON_MIDDLE), false);
        Check.that(screen.mouseDragged(click(404, 106, InputConstants.MOUSE_BUTTON_MIDDLE), -6, 10), "middle button drags");
        Check.that(screen.mouseDragged(click(404, 106, InputConstants.MOUSE_BUTTON_RIGHT), 30, 30), "right drag is taken");
        Check.that(screen.mouseDragged(click(404, 106, InputConstants.MOUSE_BUTTON_LEFT), 30, 30), "a button other than the pressed one is taken");
        Check.that(screen.mouseDragged(click(410, 96, InputConstants.MOUSE_BUTTON_MIDDLE), 6, -10), "and dragged back");
        Check.that(placed(draw(screen), session, 410, 96, underX, underZ, 1.0, false), "there and back again lands on the same spot, the other buttons moved nothing");
        screen.mouseReleased(click(410, 96, InputConstants.MOUSE_BUTTON_MIDDLE));

        // Re-entering the screen (as after the marker dialog) forgets a press that was under way.
        screen.mouseClicked(click(410, 96, InputConstants.MOUSE_BUTTON_LEFT), false);
        screen.init();
        screen.mouseDragged(click(440, 126, InputConstants.MOUSE_BUTTON_LEFT), 30, 30);
        Check.that(placed(draw(screen), session, 410, 96, underX, underZ, 1.0, false), "a press from before the screen was re-entered does not drag");

        // Scrolling out twice reaches the widest view, which is drawn smoothed; a third does nothing.
        screen.mouseScrolled(410, 96, 0, -1);
        screen.mouseScrolled(410, 96, 0, -1);
        drawn = draw(screen);
        Check.that(placed(drawn, session, 410, 96, underX, underZ, 0.25, true), "zoomed out to half a pixel per block, smoothed");
        screen.mouseScrolled(410, 96, 0, -1);
        Check.that(placed(draw(screen), session, 410, 96, underX, underZ, 0.25, true), "and no further");

        // The map key only closes the screen once it has been released after opening it.
        Check.that(screen.keyPressed(new KeyEvent(InputConstants.KEY_M, 0, 0)), "held map key is swallowed, screen stays open");
        screen.keyReleased(new KeyEvent(InputConstants.KEY_M, 0, 0));
        Field armed = WorldMapScreen.class.getDeclaredField("closeOnKey");
        armed.setAccessible(true);
        Check.that(armed.getBoolean(screen), "after release the key will close it");
        Check.that(!screen.keyPressed(new KeyEvent(InputConstants.KEY_A, 0, 0)), "other keys are not swallowed");
        Check.that(!screen.isPauseScreen(), "the game keeps running behind the map");
    }

    // ------------------------------------------------------------------ markers

    private static List<Markers.Marker> markers(MapSession session) throws Exception {
        WorldMapScreen screen = new WorldMapScreen();
        screen.width = 640;
        screen.height = 360;
        screen.init();
        Check.eq(6, draw(screen).size(), "no markers yet: background, four pages and the arrow");
        // The first checks use the small plain style; the distinct one has its own below.
        Settings.setDistinctMarkers(false);
        try {
            return markers(session, screen);
        } finally {
            Settings.setDistinctMarkers(true);
        }
    }

    private static List<Markers.Marker> markers(MapSession session, WorldMapScreen screen) throws Exception {

        // A right click opens the dialog for the block under the pointer; other buttons open nothing.
        Check.eq(null, screen.markerDialog(click(340, 160, InputConstants.MOUSE_BUTTON_LEFT)), "a left click opens no dialog");
        Check.eq(null, screen.markerDialog(click(340, 160, InputConstants.MOUSE_BUTTON_MIDDLE)), "nor a middle click");
        MarkerScreen dialog = screen.markerDialog(click(340, 160, InputConstants.MOUSE_BUTTON_RIGHT));
        Check.that(dialog != null, "a right click opens the marker dialog");
        // Half a GUI unit per block about the camera: (340, 160) is block 140.5, -90.25.
        GameTests.set(MarkerScreen.class, dialog, "name", "   ");
        dialog.commit();
        Check.eq(0, session.markers.all().length, "a blank name adds nothing");
        GameTests.set(MarkerScreen.class, dialog, "name", "  Home ");
        dialog.commit();
        Markers.Marker home = new Markers.Marker(140, -91, "Home", true);
        Check.that(!home.nameOnMinimap(), "the name is not on the minimap by default");
        Check.eq(List.of(home), List.of(session.markers.all()), "named marker on the clicked block, on the minimap by default");

        // On the world map: a square at the block's centre, under the arrow.
        List<GuiElementRenderState> drawn = draw(screen);
        Check.eq(7, drawn.size(), "world map: background, four pages, the marker and the arrow");
        float wx = (float) (320 + (140.5 - CAMERA_X) * 0.5);
        float wy = (float) (180 + (-90.5 - CAMERA_Z) * 0.5);
        GameTests.Vertices dot = vertices(drawn.get(5));
        GameTests.vertex(dot.list.get(0), wx - 2, wy - 2, Float.NaN, Float.NaN, "world map marker, top-left corner");
        GameTests.vertex(dot.list.get(2), wx + 2, wy + 2, Float.NaN, Float.NaN, "world map marker, bottom-right corner");
        Check.eq(MinimapHud.PLACED_MARKER, dot.color, "world map marker colour");
        Check.that(dot.turn() < 0, "world map marker faces the viewer");

        // On the minimap: the same block, turned with the view and cut to the square.
        MinimapHud hud = new MinimapHud();
        drawn = draw(hud);
        Check.eq(6, drawn.size(), "minimap: four pages, the marker and the arrow");
        double angle = Math.PI - Math.toRadians(YAW);
        double dx = (140.5 - CAMERA_X) * 0.5;
        double dz = (-90.5 - CAMERA_Z) * 0.5;
        float mx = (float) (97 + dx * Math.cos(angle) - dz * Math.sin(angle));
        float my = (float) (97 + dx * Math.sin(angle) + dz * Math.cos(angle));
        GameTests.vertex(vertices(drawn.get(4)).list.get(0), mx - 1.5f, my - 1.5f, Float.NaN, Float.NaN, "minimap marker position");
        Check.eq(new ScreenRectangle(5, 5, 184, 184), drawn.get(4).scissorArea(), "minimap marker is clipped to the square");
        GameTests.vertex(vertices(drawn.get(5)).list.get(0), 97f, 93f, Float.NaN, Float.NaN, "the arrow still comes last, on top");
        try {
            Settings.setOpacity(40);
            Check.eq(0x66000000 | (MinimapHud.PLACED_MARKER & 0xFFFFFF), vertices(draw(hud).get(4)).color, "minimap marker follows the opacity");
        } finally {
            Settings.setOpacity(100);
        }

        // The distinct style: a larger square on a dark, slightly larger one.
        Settings.setDistinctMarkers(true);
        try {
            drawn = draw(hud);
            Check.eq(7, drawn.size(), "distinct: four pages, outline, marker and the arrow");
            GameTests.Vertices edge = vertices(drawn.get(4));
            GameTests.vertex(edge.list.get(0), mx - 2.5f, my - 2.5f, Float.NaN, Float.NaN, "distinct: outline is 10 screen pixels wide");
            Check.eq(MinimapHud.MARKER_OUTLINE, edge.color, "distinct: outline colour");
            GameTests.Vertices inner = vertices(drawn.get(5));
            GameTests.vertex(inner.list.get(0), mx - 2f, my - 2f, Float.NaN, Float.NaN, "distinct: marker is 8 screen pixels wide, drawn over the outline");
            Check.eq(MinimapHud.PLACED_MARKER, inner.color, "distinct: marker colour");
            Check.eq(new ScreenRectangle(5, 5, 184, 184), drawn.get(4).scissorArea(), "distinct: outline is clipped to the square");
        } finally {
            Settings.setDistinctMarkers(false);
        }

        // The name on the minimap is a choice per marker, off unless ticked, and only for a marker
        // that is on the minimap. The text itself needs a font, so only the decision is checked.
        Check.that(!home.nameOnMinimap() && !namesInView(hud), "no name on the minimap unless asked for");
        dialog = screen.markerDialog(click(wx, wy, InputConstants.MOUSE_BUTTON_RIGHT));
        GameTests.set(MarkerScreen.class, dialog, "nameOnMinimap", true);
        dialog.commit();
        home = new Markers.Marker(140, -91, "Home", true, true);
        Check.eq(home, session.markers.all()[0], "ticking the second box stores it");
        draw(hud);
        Check.that(namesInView(hud), "and the minimap draws that marker's name");
        try {
            Settings.setMinimap(false);
            draw(hud);
            Check.that(!namesInView(hud), "but not with the minimap switched off");
        } finally {
            Settings.setMinimap(true);
        }
        dialog = screen.markerDialog(click(wx, wy, InputConstants.MOUSE_BUTTON_RIGHT));
        Check.eq(true, GameTests.get(MarkerScreen.class, dialog, "nameOnMinimap"), "the dialog opens with the stored choice");
        GameTests.set(MarkerScreen.class, dialog, "onMinimap", false);
        dialog.commit();
        Check.eq(new Markers.Marker(140, -91, "Home", false, false), session.markers.all()[0], "a marker taken off the minimap loses the name choice with it");
        GameTests.set(MarkerScreen.class, dialog, "onMinimap", true);
        GameTests.set(MarkerScreen.class, dialog, "nameOnMinimap", false);
        GameTests.set(MarkerScreen.class, dialog, "existing", session.markers.all()[0]);
        dialog.commit();
        home = new Markers.Marker(140, -91, "Home", true);
        Check.eq(home, session.markers.all()[0], "back to how it was");

        // A marker for the full map only, and one far away: neither is on the minimap.
        dialog = screen.markerDialog(click(300, 200, InputConstants.MOUSE_BUTTON_RIGHT));
        GameTests.set(MarkerScreen.class, dialog, "name", "Mine");
        GameTests.set(MarkerScreen.class, dialog, "onMinimap", false);
        dialog.commit();
        session.markers.add(new Markers.Marker(5000, 5000, "Far", true));
        session.markersChanged();
        Check.eq(8, draw(screen).size(), "world map shows both markers in view, not the far one");
        Check.eq(6, draw(hud).size(), "minimap still shows only the one meant for it");

        // Right-clicking a marker's square opens it again: change it, then delete it.
        dialog = screen.markerDialog(click(wx + 2.5, wy - 2.5, InputConstants.MOUSE_BUTTON_RIGHT));
        Check.that(dialog != null && home.equals(GameTests.get(MarkerScreen.class, dialog, "existing")), "a click near the square picks that marker");
        GameTests.set(MarkerScreen.class, dialog, "onMinimap", false);
        dialog.commit();
        Markers.Marker homeMapOnly = new Markers.Marker(140, -91, "Home", false);
        Check.eq(homeMapOnly, session.markers.all()[0], "changed in place, minimap switched off");
        Check.eq(5, draw(hud).size(), "and gone from the minimap");
        Check.eq(null, GameTests.get(MarkerScreen.class, screen.markerDialog(click(wx + 4, wy, InputConstants.MOUSE_BUTTON_RIGHT)), "existing"),
                "a click further off is a new marker");
        dialog = screen.markerDialog(click(wx, wy, InputConstants.MOUSE_BUTTON_RIGHT));
        dialog.remove();
        Check.eq(2, session.markers.all().length, "deleted");
        Check.that(session.markers.all()[0].name().equals("Mine"), "the others stay");

        // A left click on a marker removes it: press and release on it without moving the map.
        session.markers.add(home);
        session.markersChanged();
        Check.eq(3, session.markers.all().length, "three markers before clicking");
        screen.mouseClicked(click(wx + 60, wy + 60, InputConstants.MOUSE_BUTTON_LEFT), false);
        screen.mouseReleased(click(wx + 60, wy + 60, InputConstants.MOUSE_BUTTON_LEFT));
        Check.eq(3, session.markers.all().length, "a click on empty map removes nothing");
        screen.mouseClicked(click(wx, wy, InputConstants.MOUSE_BUTTON_MIDDLE), false);
        screen.mouseReleased(click(wx, wy, InputConstants.MOUSE_BUTTON_MIDDLE));
        Check.eq(3, session.markers.all().length, "a middle click on a marker removes nothing");
        // A drag that starts on a marker moves the map and keeps the marker.
        screen.mouseClicked(click(wx, wy, InputConstants.MOUSE_BUTTON_LEFT), false);
        screen.mouseDragged(click(wx + 20, wy, InputConstants.MOUSE_BUTTON_LEFT), 20, 0);
        screen.mouseReleased(click(wx + 20, wy, InputConstants.MOUSE_BUTTON_LEFT));
        Check.eq(3, session.markers.all().length, "a drag starting on a marker keeps it");
        screen.mouseClicked(click(wx + 20, wy, InputConstants.MOUSE_BUTTON_MIDDLE), false);
        screen.mouseDragged(click(wx, wy, InputConstants.MOUSE_BUTTON_MIDDLE), -20, 0);
        screen.mouseReleased(click(wx, wy, InputConstants.MOUSE_BUTTON_MIDDLE));
        // A pointer that shakes by a pixel between press and release is still a click.
        screen.mouseClicked(click(wx, wy, InputConstants.MOUSE_BUTTON_LEFT), false);
        screen.mouseDragged(click(wx + 0.5, wy, InputConstants.MOUSE_BUTTON_LEFT), 0.5, 0);
        screen.mouseReleased(click(wx + 0.5, wy, InputConstants.MOUSE_BUTTON_LEFT));
        Check.eq(2, session.markers.all().length, "a left click on a marker removes it");
        Check.that(!List.of(session.markers.all()).contains(home), "and it is the clicked one");
        screen.mouseDragged(click(wx - 0.5, wy, InputConstants.MOUSE_BUTTON_LEFT), -0.5, 0);

        // What a minimap frame costs with markers on it.
        session.markers.add(home);
        session.markersChanged();
        GuiRenderState state = new GuiRenderState();
        int frames = 200_000;
        for (int i = 0; i < 20_000; i++) {
            state.reset();
            hud.submit(state, DeltaTracker.ONE);
        }
        long emptyBytes = allocated();
        for (int i = 0; i < frames; i++) {
            state.reset();
        }
        emptyBytes = allocated() - emptyBytes;
        long bytes = allocated();
        long nanos = System.nanoTime();
        for (int i = 0; i < frames; i++) {
            state.reset();
            hud.submit(state, DeltaTracker.ONE);
        }
        nanos = System.nanoTime() - nanos;
        bytes = allocated() - bytes;
        double perFrame = (bytes - emptyBytes) / (double) frames;
        System.out.printf("  minimap per frame with %d markers: %.0f ns, %.0f bytes allocated%n",
                session.markers.all().length, nanos / (double) frames, perFrame);
        Check.that(perFrame < 400, "markers add no allocation to a minimap frame (" + Math.round(perFrame) + " bytes)");
        return List.of(session.markers.all());
    }

    private static boolean namesInView(MinimapHud hud) throws Exception {
        return (boolean) GameTests.get(MinimapHud.class, hud, "namesInView");
    }

    private static MouseButtonEvent click(double x, double y, int button) {
        return new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0));
    }

    private static List<GuiElementRenderState> draw(WorldMapScreen screen) {
        GuiRenderState state = new GuiRenderState();
        screen.submit(state, 1f);
        return elements(state);
    }

    /**
     * Whether the four recorded regions are drawn where a north-up view puts them, given that the
     * world position (worldX, worldZ) is at the screen position (screenX, screenY).
     */
    private static boolean placed(List<GuiElementRenderState> drawn, MapSession session, double screenX, double screenY,
                                  double worldX, double worldZ, double scale, boolean smooth) {
        int found = 0;
        for (GuiElementRenderState quad : drawn) {
            TextureSetup texture = quad.textureSetup();
            for (int rz = -1; rz <= 0; rz++) {
                for (int rx = -1; rx <= 0; rx++) {
                    GpuPages.Handle handle = (GpuPages.Handle) session.page(Regions.key(rx, rz)).gpu;
                    if (texture != (smooth ? handle.linear : handle.nearest)) {
                        continue;
                    }
                    float[] corner = vertices(quad).list.get(0);
                    double sx = screenX + (rx * 512 - worldX) * scale;
                    double sy = screenY + (rz * 512 - worldZ) * scale;
                    if (Math.abs(corner[0] - sx) < 0.01 && Math.abs(corner[1] - sy) < 0.01 && quad.scissorArea() == null) {
                        found++;
                    }
                }
            }
        }
        return found == 4;
    }

    // ------------------------------------------------------------------ helpers

    /** Ticks the session until every region around the origin has its texture; returns the ticks used. */
    private static int settle(MapSession session) {
        for (int tick = 1; tick <= 200; tick++) {
            session.tick();
            int ready = 0;
            for (int rz = -1; rz <= 0; rz++) {
                for (int rx = -1; rx <= 0; rx++) {
                    RegionPage page = session.page(Regions.key(rx, rz));
                    if (page != null && page.gpu instanceof GpuPages.Handle handle && handle.ready) {
                        ready++;
                    }
                }
            }
            if (ready == 4) {
                return tick;
            }
        }
        return Integer.MAX_VALUE;
    }

    private static List<GuiElementRenderState> elements(GuiRenderState state) {
        List<GuiElementRenderState> list = new ArrayList<>();
        state.forEachElement(list::add, GuiRenderState.TraverseRange.ALL);
        return list;
    }

    private static GameTests.Vertices vertices(GuiElementRenderState quad) {
        GameTests.Vertices v = new GameTests.Vertices();
        quad.buildVertices(v);
        return v;
    }

    @SuppressWarnings("unchecked")
    private static <T> T bare(Class<T> type) throws Exception {
        return (T) GameTests.unsafe().allocateInstance(type);
    }

    private static long allocated() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getThreadAllocatedBytes(Thread.currentThread().threadId());
    }
}
