package dev.lightmap.mc;

import dev.lightmap.Config;
import dev.lightmap.LightMapClient;
import dev.lightmap.core.MapView;
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

import java.util.Arrays;

/**
 * The full-screen map: the same pages the minimap draws, north up. Hold a mouse button and drag to move it,
 * scroll to zoom.
 *
 * <p>Regions that were recorded earlier and are no longer in memory are loaded from disk as they
 * scroll into view.
 */
public final class WorldMapScreen extends Screen {
    private static final float MIN_ZOOM = 0.5f;
    private static final float MAX_ZOOM = 16f;

    private final MapView view = new MapView();
    private final MapQuad background = new MapQuad();
    private final MapQuad marker = new MapQuad();
    private MapQuad[] pageQuads = new MapQuad[0];
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

    public WorldMapScreen() {
        super(Component.translatable("lightmap.world_map"));
    }

    @Override
    protected void init() {
        area = new ScreenRectangle(0, 0, width, height);
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

        Entity camera = minecraft.getCameraEntity();
        if (camera != null && camera.level() == session.level) {
            float heading = camera.getViewYRot(partial) * Mth.DEG_TO_RAD;
            marker.arrow(view.screenX(camera.getX(), camera.getZ()), view.screenY(camera.getX(), camera.getZ()),
                    -Mth.sin(heading), Mth.cos(heading), 10f / guiScale(), MinimapHud.MARKER, area);
            state.addGlyphToCurrentLayer(marker);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // The screen has no widgets; taking the click keeps anything else from acting on it.
        return true;
    }

    /** Holding any mouse button and moving the pointer moves the map with it. */
    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
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
