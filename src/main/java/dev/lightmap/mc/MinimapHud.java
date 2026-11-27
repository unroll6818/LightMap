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
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;

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
    /** The colour of the player's named markers, on both maps. */
    static final int PLACED_MARKER = 0xFFF2C230;

    private final MapView view = new MapView();
    private final MapQuad marker = new MapQuad();
    private MapQuad[] pageQuads = new MapQuad[0];
    private MapQuad[] markerQuads = new MapQuad[0];
    private ScreenRectangle area;
    private int areaLeft;
    private int areaSize;

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        submit(((GuiGraphicsExtractorAccessor) graphics).lightmap$guiRenderState(), delta);
    }

    /** Adds this frame's quads to the GUI render state. */
    void submit(GuiRenderState state, DeltaTracker delta) {
        int opacity = Settings.opacity();
        if (!Settings.minimap() || opacity <= 0) {
            return;
        }
        MapSession session = LightMapClient.session();
        Minecraft minecraft = Minecraft.getInstance();
        Entity camera = minecraft.getCameraEntity();
        if (session == null || camera == null || camera.level() != session.level
                || minecraft.gui.screen() instanceof WorldMapScreen) {
            return;
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
        float half = 3f / guiScale;
        int markerColor = alpha | (PLACED_MARKER & 0xFFFFFF);
        int dots = 0;
        for (Markers.Marker m : session.markers.all()) {
            if (!m.onMinimap()) {
                continue;
            }
            float sx = view.screenX(m.x() + 0.5, m.z() + 0.5);
            float sy = view.screenY(m.x() + 0.5, m.z() + 0.5);
            if (sx + half < left || sx - half > left + size || sy + half < left || sy - half > left + size) {
                continue;
            }
            markerQuads = MapQuad.pool(markerQuads, dots);
            MapQuad dot = markerQuads[dots++];
            dot.dot(sx, sy, half, markerColor, area, area);
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
    }

    private MapQuad quad(int index) {
        pageQuads = MapQuad.pool(pageQuads, index);
        return pageQuads[index];
    }
}
