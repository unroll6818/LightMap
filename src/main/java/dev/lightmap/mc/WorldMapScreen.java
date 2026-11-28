package dev.lightmap.mc;

import com.mojang.blaze3d.platform.InputConstants;
import dev.lightmap.Config;
import dev.lightmap.LightMapClient;
import dev.lightmap.core.MapView;
import dev.lightmap.core.Markers;
import dev.lightmap.core.RegionPage;
import dev.lightmap.core.Regions;
import dev.lightmap.mixin.GuiGraphicsExtractorAccessor;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;

/**
 * The full-screen map: the same pages the minimap draws, north up. Hold the left or middle mouse
 * button and drag to move it, scroll to zoom, right-click to add a named marker or change one,
 * left-click a marker to remove it.
 *
 * <p>Regions that were recorded earlier and are no longer in memory are loaded from disk as they
 * scroll into view.
 */
public final class WorldMapScreen extends Screen {
    private static final float MIN_ZOOM = 0.5f;
    private static final float MAX_ZOOM = 16f;
    /** Pointer travel, in screen pixels, up to which a press and release still counts as a click. */
    private static final float CLICK_SLOP = 3f;

    private final MapView view = new MapView();
    private final MapQuad background = new MapQuad();
    private final MapQuad marker = new MapQuad();
    private MapQuad[] pageQuads = new MapQuad[0];
    private MapQuad[] markerQuads = new MapQuad[0];
    private ScreenRectangle area = ScreenRectangle.empty();

    private boolean placed;
    private double centreX;
    private double centreZ;
    /** Screen pixels per block; always a power of two. */
    private float zoom = 1f;
    /**
     * Whether the map key closes the screen. Only once it has been let go after opening it:
     * holding the key down sends repeats, which must not flip the screen shut again.
     */
    private boolean closeOnKey;
    /**
     * The button that was pressed on this screen and is moving the map, or -1. The game's own
     * "a button is held" state cannot be trusted for this: it is set by any press anywhere, such
     * as the click that joined the world, and is only cleared by a release the game sees, which
     * it does not when the pointer was captured for play in between. So only a press that this
     * screen received starts a drag.
     */
    private int dragButton = -1;
    /** How far the pointer has travelled since the press, in GUI units, to tell a click from a drag. */
    private double dragTravel;
    /** The marker under the pointer when the left button went down, if any. */
    private Markers.@Nullable Marker pressedMarker;

    public WorldMapScreen() {
        super(Component.translatable("lightmap.world_map"));
    }

    @Override
    protected void init() {
        area = new ScreenRectangle(0, 0, width, height);
        dragButton = -1;
        pressedMarker = null;
        if (!placed) {
            Entity camera = minecraft.getCameraEntity();
            if (camera != null) {
                centreX = camera.getX();
                centreZ = camera.getZ();
            }
            placed = true;
        }
        while (zoom < MAX_ZOOM && !fits(zoom)) {
            zoom *= 2f;
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
        // The map covers the whole screen itself; the default blur and dimming would be wasted.
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
        submit(((GuiGraphicsExtractorAccessor) graphics).lightmap$guiRenderState(), partial);
        labels(graphics);
    }

    /**
     * The markers' names, above their squares. Text goes through the game's own text drawing,
     * which allocates a little per name; that is only while this screen is open.
     */
    private void labels(GuiGraphicsExtractor graphics) {
        MapSession session = LightMapClient.session();
        if (session == null) {
            return;
        }
        float half = markerHalf();
        for (Markers.Marker m : session.markers.all()) {
            float sx = view.screenX(m.x() + 0.5, m.z() + 0.5);
            float sy = view.screenY(m.x() + 0.5, m.z() + 0.5);
            if (sx < -100 || sx > width + 100 || sy < 0 || sy > height + font.lineHeight) {
                continue;
            }
            graphics.text(font, m.name(), Math.round(sx) - font.width(m.name()) / 2,
                    Math.round(sy - half) - font.lineHeight - 1, 0xFFFFFFFF, true);
        }
    }

    /** Adds this frame's quads to the GUI render state. */
    void submit(GuiRenderState state, float partial) {
        background.fill(0, 0, width, height, MinimapHud.BACKDROP, area);
        state.addGuiElement(background);

        MapSession session = LightMapClient.session();
        if (session == null) {
            return;
        }
        float scale = zoom / guiScale();
        view.set(centreX, centreZ, width * 0.5f, height * 0.5f, scale, 0f);
        double halfWidth = width * 0.5 / scale;
        double halfHeight = height * 0.5 / scale;
        int rx0 = Regions.ofBlock(Mth.floor(centreX - halfWidth));
        int rx1 = Regions.ofBlock(Mth.floor(centreX + halfWidth));
        int rz0 = Regions.ofBlock(Mth.floor(centreZ - halfHeight));
        int rz1 = Regions.ofBlock(Mth.floor(centreZ + halfHeight));
        boolean smooth = zoom < 1f;
        long now = System.nanoTime();
        int used = 0;
        for (int rz = rz0; rz <= rz1; rz++) {
            for (int rx = rx0; rx <= rx1; rx++) {
                long key = Regions.key(rx, rz);
                RegionPage page = session.page(key);
                if (page == null) {
                    session.want(key, now);
                    continue;
                }
                page.keepAliveAt = now;
                if (!(page.gpu instanceof GpuPages.Handle handle) || !handle.ready) {
                    continue;
                }
                MapQuad quad = pageQuad(used++);
                quad.page(view, rx, rz, smooth ? handle.linear : handle.nearest, -1, null, area);
                state.addGlyphToCurrentLayer(quad);
            }
        }

        float half = markerHalf();
        int dots = 0;
        for (Markers.Marker m : session.markers.all()) {
            float sx = view.screenX(m.x() + 0.5, m.z() + 0.5);
            float sy = view.screenY(m.x() + 0.5, m.z() + 0.5);
            if (sx + half < 0 || sx - half > width || sy + half < 0 || sy - half > height) {
                continue;
            }
            markerQuads = MapQuad.pool(markerQuads, dots);
            MapQuad dot = markerQuads[dots++];
            dot.dot(sx, sy, half, m.argb(), null, area);
            state.addGlyphToCurrentLayer(dot);
        }

        Entity camera = minecraft.getCameraEntity();
        if (camera != null && camera.level() == session.level) {
            float heading = camera.getViewYRot(partial) * Mth.DEG_TO_RAD;
            marker.arrow(view.screenX(camera.getX(), camera.getZ()), view.screenY(camera.getX(), camera.getZ()),
                    -Mth.sin(heading), Mth.cos(heading), 10f / guiScale(), MinimapHud.MARKER, area);
            state.addGlyphToCurrentLayer(marker);
        }
    }

    /**
     * A right click opens the marker dialog: for the marker under the pointer if there is one,
     * otherwise for a new marker on the block under the pointer. The left or middle button starts
     * moving the map; what a left press on a marker means is decided when it is let go.
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        MarkerScreen dialog = markerDialog(event);
        if (dialog != null) {
            minecraft.gui.setScreen(dialog);
        } else if (event.button() == InputConstants.MOUSE_BUTTON_LEFT || event.button() == InputConstants.MOUSE_BUTTON_MIDDLE) {
            dragButton = event.button();
            dragTravel = 0;
            MapSession session = LightMapClient.session();
            pressedMarker = session != null && event.button() == InputConstants.MOUSE_BUTTON_LEFT
                    ? markerAt(session, event.x(), event.y()) : null;
        }
        // The screen has no widgets; taking the click keeps anything else from acting on it.
        return true;
    }

    /**
     * Letting the left button go on the marker it was pressed on, without having moved the map,
     * removes that marker.
     */
    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == dragButton) {
            Markers.Marker pressed = pressedMarker;
            MapSession session = LightMapClient.session();
            if (pressed != null && session != null && dragTravel <= CLICK_SLOP / guiScale()
                    && markerAt(session, event.x(), event.y()) == pressed) {
                session.markers.replace(pressed, null);
                session.markersChanged();
            }
            dragButton = -1;
            pressedMarker = null;
        }
        return true;
    }

    /** The marker dialog a click opens, or null if it opens none. */
    @Nullable MarkerScreen markerDialog(MouseButtonEvent event) {
        MapSession session = LightMapClient.session();
        if (event.button() != InputConstants.MOUSE_BUTTON_RIGHT || session == null) {
            return null;
        }
        Markers.Marker hit = markerAt(session, event.x(), event.y());
        if (hit != null) {
            return new MarkerScreen(this, session, hit, hit.x(), hit.z());
        }
        float scale = zoom / guiScale();
        int x = Mth.floor(centreX + (event.x() - width * 0.5) / scale);
        int z = Mth.floor(centreZ + (event.y() - height * 0.5) / scale);
        return new MarkerScreen(this, session, null, x, z);
    }

    /** The marker whose square is under a screen position, give or take a little, or null. */
    Markers.@Nullable Marker markerAt(MapSession session, double x, double y) {
        float scale = zoom / guiScale();
        double reach = markerHalf() + 2.0 / guiScale();
        Markers.Marker best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Markers.Marker m : session.markers.all()) {
            double dx = width * 0.5 + (m.x() + 0.5 - centreX) * scale - x;
            double dy = height * 0.5 + (m.z() + 0.5 - centreZ) * scale - y;
            double distance = Math.max(Math.abs(dx), Math.abs(dy));
            if (distance <= reach && distance < bestDistance) {
                best = m;
                bestDistance = distance;
            }
        }
        return best;
    }

    /**
     * Holding the left or middle mouse button and moving the pointer moves the map with it. The
     * right button places markers and does not move the map.
     */
    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragButton == -1 || event.button() != dragButton) {
            return true;
        }
        dragTravel += Math.abs(dx) + Math.abs(dy);
        float scale = zoom / guiScale();
        centreX -= dx / scale;
        centreZ -= dy / scale;
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        float target = scrollY > 0 ? zoom * 2f : scrollY < 0 ? zoom * 0.5f : zoom;
        target = Mth.clamp(target, MIN_ZOOM, MAX_ZOOM);
        if (target == zoom || !fits(target)) {
            return true;
        }
        // Keep the block under the cursor where it is.
        double offsetX = mouseX - width * 0.5;
        double offsetY = mouseY - height * 0.5;
        float before = zoom / guiScale();
        float after = target / guiScale();
        centreX += offsetX / before - offsetX / after;
        centreZ += offsetY / before - offsetY / after;
        zoom = target;
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (LightMapClient.openMapKey().matches(event)) {
            if (closeOnKey) {
                onClose();
            }
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        if (LightMapClient.openMapKey().matches(event)) {
            closeOnKey = true;
        }
        return super.keyReleased(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Whether the regions in view at a zoom level fit comfortably in the page cache. */
    private boolean fits(float zoomLevel) {
        float scale = zoomLevel / guiScale();
        long across = (long) Math.ceil(width / scale / Regions.SIZE) + 1;
        long down = (long) Math.ceil(height / scale / Regions.SIZE) + 1;
        return across * down <= Config.MAX_PAGES * 3L / 4;
    }

    /** Half the side of a marker's square, in GUI units: a few screen pixels at any GUI scale. */
    private float markerHalf() {
        return 4f / guiScale();
    }

    private int guiScale() {
        return Math.max(1, minecraft.getWindow().getGuiScale());
    }

    private MapQuad pageQuad(int index) {
        if (index >= pageQuads.length) {
            int from = pageQuads.length;
            pageQuads = Arrays.copyOf(pageQuads, Math.max(16, index + 8));
            for (int i = from; i < pageQuads.length; i++) {
                pageQuads[i] = new MapQuad();
            }
        }
        return pageQuads[index];
    }
}
