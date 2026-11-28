package dev.lightmap.mc;

import dev.lightmap.Config;
import dev.lightmap.LightMapClient;
import dev.lightmap.Settings;
import dev.lightmap.core.MapView;
import dev.lightmap.core.Markers;
import dev.lightmap.core.RegionPage;
import dev.lightmap.core.Regions;
import dev.lightmap.mixin.GuiGraphicsExtractorAccessor;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix3x2fStack;

import java.util.List;

/**
 * The minimap: the region pages around the camera, drawn as textured quads straight into the
 * game's GUI pass.
 *
 * <p>There is no off-screen target and no extra render pass. The map image already lives in the
 * page textures, so a frame only has to say where the up to four regions in view go: the rotation
 * and zoom are applied to the quad corners, and the GUI renderer's scissor rectangle cuts the
 * result down to the square. That is a handful of vertices per frame and no allocation.
 *
 * <p>Every region in view is drawn exactly once, as its page or, while it has none, as a blank
 * square. Nothing overlaps, so a see-through minimap is evenly see-through.
 */
public final class MinimapHud implements HudElement {
    /** The colour pages use for unexplored ground, so regions without a page blend in. */
    static final int BACKDROP = 0xFF141518;
    static final int MARKER = 0xFFE8463A;
    /** The outline around a marker in the medium and large sizes. */
    static final int MARKER_OUTLINE = 0xFF101114;
    /** Side of another player's face on the minimap, in screen pixels: twice the skin's 8 texels. */
    static final int HEAD_PIXELS = 16;
    /** Height of a line of the game's text, in GUI units at scale 1. */
    private static final int LINE = 9;

    private final MapView view = new MapView();
    private final MapQuad marker = new MapQuad();
    private final Painter painter = new Painter();
    private MapQuad[] pageQuads = new MapQuad[0];
    private MapQuad[] markerQuads = new MapQuad[0];
    private ScreenRectangle area;
    private int areaLeft;
    private int areaSize;
    /** Set by {@link #submit} when a marker in view wants its name drawn, so most frames skip that pass. */
    private boolean namesInView;
    /** This frame's partial tick, GUI scale and opacity as colour alpha, kept by {@link #submit} for {@link #overlay}. */
    private float framePartial;
    private int frameGuiScale = 1;
    private int frameAlpha;

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        if (submit(((GuiGraphicsExtractorAccessor) graphics).lightmap$guiRenderState(), delta)
                && (namesInView || Settings.players() != Settings.Players.OFF)) {
            graphics.enableScissor(areaLeft, areaLeft, areaLeft + areaSize, areaLeft + areaSize);
            painter.graphics = graphics;
            overlay(painter);
            painter.graphics = null;
            graphics.disableScissor();
        }
    }

    /** Draws the things on top of the map that are not our own quads. */
    interface Overlay {
        /** One line of text, centred on {@code x} with its bottom edge at {@code bottom}. */
        void label(String text, float x, float bottom, float scale, int argb);

        /** A player's face as a square of side {@code size} centred on (x, y). */
        void face(AbstractClientPlayer player, float x, float y, float size, int argb);
    }

    /**
     * Draws through the game's own text and texture drawing, which allocates a little per item
     * per frame.
     *
     * <p>Everything is placed through the pose matrix at its exact fractional position, the same
     * one the marker quads use. Whole GUI units would make a label hop a pixel or more at a time
     * while its marker glides, which reads as the label trailing behind.
     */
    private static final class Painter implements Overlay {
        GuiGraphicsExtractor graphics;

        @Override
        public void label(String text, float x, float bottom, float scale, int argb) {
            Font font = Minecraft.getInstance().font;
            Matrix3x2fStack pose = graphics.pose();
            pose.pushMatrix();
            pose.translate(x - font.width(text) * scale * 0.5f, bottom - LINE * scale);
            pose.scale(scale, scale);
            graphics.text(font, text, 0, 0, argb, true);
            pose.popMatrix();
        }

        @Override
        public void face(AbstractClientPlayer player, float x, float y, float size, int argb) {
            // The face is 8 by 8 texels; drawn at 8 units and scaled to the wanted size.
            Matrix3x2fStack pose = graphics.pose();
            pose.pushMatrix();
            pose.translate(x - size * 0.5f, y - size * 0.5f);
            pose.scale(size / 8f, size / 8f);
            PlayerFaceExtractor.extractRenderState(graphics, player.getSkin(), 0, 0, 8, argb);
            pose.popMatrix();
        }
    }

    /**
     * What sits on top of the map: the names of markers that asked for one, and other players'
     * faces and names. Only called for a frame in which {@link #submit} drew the minimap; the
     * caller cuts everything to the minimap's square. The cost exists only while such a marker
     * or a player is in view.
     */
    void overlay(Overlay out) {
        MapSession session = LightMapClient.session();
        if (session == null) {
            return;
        }
        int right = areaLeft + areaSize;
        int color = frameAlpha | 0xFFFFFF;
        float unit = 1f / frameGuiScale;
        float textScale = Settings.labelScale() / 100f;

        if (namesInView) {
            float half = markerHalf(frameGuiScale);
            for (Markers.Marker m : session.markers.all()) {
                if (!m.onMinimap() || !m.nameOnMinimap()) {
                    continue;
                }
                float sx = view.screenX(m.x() + 0.5, m.z() + 0.5);
                float sy = view.screenY(m.x() + 0.5, m.z() + 0.5);
                if (sx < areaLeft || sx > right || sy < areaLeft || sy > right) {
                    continue;
                }
                out.label(m.name(), sx, sy - half - 2 * unit, textScale, color);
            }
        }

        Settings.Players mode = Settings.players();
        if (mode != Settings.Players.OFF) {
            // Everyone the server has told this client about, whatever they are doing: sneaking
            // and invisibility only change how the game draws a player, not whether it knows them.
            Minecraft minecraft = Minecraft.getInstance();
            List<AbstractClientPlayer> players = session.level.players();
            Entity camera = minecraft.getCameraEntity();
            float head = HEAD_PIXELS * unit;
            for (int i = 0; i < players.size(); i++) {
                AbstractClientPlayer player = players.get(i);
                if (player == camera || player == minecraft.player) {
                    continue;
                }
                double px = Mth.lerp((double) framePartial, player.xo, player.getX());
                double pz = Mth.lerp((double) framePartial, player.zo, player.getZ());
                float sx = view.screenX(px, pz);
                float sy = view.screenY(px, pz);
                if (sx < areaLeft || sx > right || sy < areaLeft || sy > right) {
                    continue;
                }
                if (mode.heads()) {
                    out.face(player, sx, sy, head, color);
                }
                if (mode.names()) {
                    // Above the face, or centred on the player when there is no face.
                    float bottom = mode.heads() ? sy - head * 0.5f - unit : sy + LINE * textScale * 0.5f;
                    out.label(player.getPlainTextName(), sx, bottom, textScale, color);
                }
            }
        }
    }

    /** Half the side of a marker's square in GUI units, for the size chosen in the settings. */
    static float markerHalf(int guiScale) {
        return switch (Settings.markerSize()) {
            case SMALL -> 3f;
            case MEDIUM -> 4f;
            case LARGE -> 6f;
        } / guiScale;
    }

    /** Adds this frame's quads to the GUI render state. Returns whether the minimap was drawn. */
    boolean submit(GuiRenderState state, DeltaTracker delta) {
        namesInView = false;
        int opacity = Settings.opacity();
        if (!Settings.minimap() || opacity <= 0) {
            return false;
        }
        MapSession session = LightMapClient.session();
        Minecraft minecraft = Minecraft.getInstance();
        Entity camera = minecraft.getCameraEntity();
        if (session == null || camera == null || camera.level() != session.level
                || minecraft.gui.screen() instanceof WorldMapScreen) {
            return false;
        }

        int guiScale = Math.max(1, minecraft.getWindow().getGuiScale());
        int size = Math.max(16, Settings.size() / guiScale);
        int left = Config.MINIMAP_MARGIN / guiScale;
        if (area == null || left != areaLeft || size != areaSize) {
            area = new ScreenRectangle(left, left, size, size);
            areaLeft = left;
            areaSize = size;
        }
        boolean rotate = Settings.rotate();
        // Opacity goes into the alpha of every quad's colour: 100 percent is 255.
        int alpha = (opacity * 255 + 50) / 100 << 24;
        int pageColor = alpha | 0xFFFFFF;
        int blankColor = alpha | (BACKDROP & 0xFFFFFF);

        float partial = delta.getGameTimeDeltaPartialTick(false);
        framePartial = partial;
        frameGuiScale = guiScale;
        frameAlpha = alpha;
        double x = Mth.lerp((double) partial, camera.xo, camera.getX());
        double z = Mth.lerp((double) partial, camera.zo, camera.getZ());
        float yaw = camera.getViewYRot(partial);
        float centre = left + size * 0.5f;
        float scale = Config.MINIMAP_ZOOM / guiScale;
        view.set(x, z, centre, centre, scale, rotate ? MapView.headingUp(yaw) : 0f);

        double reach = view.coverRadius(size, size);
        int rx0 = Regions.ofBlock(Mth.floor(x - reach));
        int rx1 = Regions.ofBlock(Mth.floor(x + reach));
        int rz0 = Regions.ofBlock(Mth.floor(z - reach));
        int rz1 = Regions.ofBlock(Mth.floor(z + reach));
        // Nearest sampling is only crisp when texels land on pixels: north up, whole-number zoom.
        boolean smooth = rotate || Config.MINIMAP_ZOOM != Math.rint(Config.MINIMAP_ZOOM);
        long now = System.nanoTime();
        int used = 0;
        for (int rz = rz0; rz <= rz1; rz++) {
            for (int rx = rx0; rx <= rx1; rx++) {
                long key = Regions.key(rx, rz);
                RegionPage page = session.page(key);
                MapQuad quad = quad(used);
                if (page == null) {
                    session.want(key, now);
                    quad.blank(view, rx, rz, blankColor, area, area);
                } else {
                    page.keepAliveAt = now;
                    if (page.gpu instanceof GpuPages.Handle handle && handle.ready) {
                        quad.page(view, rx, rz, smooth ? handle.linear : handle.nearest, pageColor, area, area);
                    } else {
                        quad.blank(view, rx, rz, blankColor, area, area);
                    }
                }
                // The first quad goes through the normal path, which finds the right layer for
                // this part of the screen. The others and the marker never overlap it or each
                // other, so they join that layer directly instead of opening one layer each.
                if (used++ == 0) {
                    state.addGuiElement(quad);
                } else {
                    state.addGlyphToCurrentLayer(quad);
                }
            }
        }

        // The player's markers that are meant for the minimap, under the arrow, cut to the square.
        // Small: a plain square. Medium and large: a dark outline too, which is a second,
        // slightly larger square under it.
        boolean outlined = Settings.markerSize() != Settings.MarkerSize.SMALL;
        float half = markerHalf(guiScale);
        float outline = half + 1f / guiScale;
        int outlineColor = alpha | (MARKER_OUTLINE & 0xFFFFFF);
        int dots = 0;
        for (Markers.Marker m : session.markers.all()) {
            if (!m.onMinimap()) {
                continue;
            }
            float sx = view.screenX(m.x() + 0.5, m.z() + 0.5);
            float sy = view.screenY(m.x() + 0.5, m.z() + 0.5);
            if (sx + outline < left || sx - outline > left + size || sy + outline < left || sy - outline > left + size) {
                continue;
            }
            namesInView |= m.nameOnMinimap();
            if (outlined) {
                markerQuads = MapQuad.pool(markerQuads, dots);
                MapQuad edge = markerQuads[dots++];
                edge.dot(sx, sy, outline, outlineColor, area, area);
                state.addGlyphToCurrentLayer(edge);
            }
            markerQuads = MapQuad.pool(markerQuads, dots);
            MapQuad dot = markerQuads[dots++];
            dot.dot(sx, sy, half, alpha | (m.argb() & 0xFFFFFF), area, area);
            state.addGlyphToCurrentLayer(dot);
        }

        float dirX = 0f;
        float dirY = -1f;
        if (!rotate) {
            float heading = yaw * Mth.DEG_TO_RAD;
            dirX = -Mth.sin(heading);
            dirY = Mth.cos(heading);
        }
        marker.arrow(centre, centre, dirX, dirY, 8f / guiScale, alpha | (MARKER & 0xFFFFFF), area);
        state.addGlyphToCurrentLayer(marker);
        return true;
    }

    private MapQuad quad(int index) {
        pageQuads = MapQuad.pool(pageQuads, index);
        return pageQuads[index];
    }
}
