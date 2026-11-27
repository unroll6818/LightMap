package dev.lightmap.mc;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.lightmap.core.MapView;
import dev.lightmap.core.Regions;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;

/**
 * One quad for the game's GUI renderer, either a map page or a flat colour.
 *
 * <p>The GUI is rebuilt from render states every frame. The stock drawing helpers allocate a new
 * state, matrix and rectangle per call; the map instead keeps a handful of these and only rewrites
 * their corners, so drawing it allocates nothing. Corners are stored already transformed, in GUI
 * coordinates, in the order the GUI pipelines expect (top-left, bottom-left, bottom-right,
 * top-right before rotation), which keeps them front-facing under any rotation.
 */
final class MapQuad implements GuiElementRenderState {
    private float x0, y0, x1, y1, x2, y2, x3, y3;
    private int color = -1;
    private @Nullable TextureSetup texture;
    private @Nullable ScreenRectangle scissor;
    private @Nullable ScreenRectangle bounds;

    /**
     * Returns {@code quads}, or a longer copy of it, with a quad at {@code index}. Quads are only
     * ever added, so a frame that needs no more than an earlier one allocates nothing.
     */
    static MapQuad[] pool(MapQuad[] quads, int index) {
        if (index < quads.length) {
            return quads;
        }
        int from = quads.length;
        MapQuad[] grown = Arrays.copyOf(quads, Math.max(4, Math.max(index + 1, from * 2)));
        for (int i = from; i < grown.length; i++) {
            grown[i] = new MapQuad();
        }
        return grown;
    }

    /**
     * A region's page, placed by the view transform and clipped to {@code clip} if given. The
     * colour multiplies the texture: white for the page as it is, a lower alpha to see through it.
     */
    void page(MapView view, int rx, int rz, TextureSetup texture, int argb, @Nullable ScreenRectangle clip, ScreenRectangle bounds) {
        region(view, rx, rz);
        this.color = argb;
        this.texture = texture;
        this.scissor = clip;
        this.bounds = bounds;
    }

    /** A region with nothing to show yet: the same square as its page would cover, in one colour. */
    void blank(MapView view, int rx, int rz, int argb, @Nullable ScreenRectangle clip, ScreenRectangle bounds) {
        region(view, rx, rz);
        this.color = argb;
        this.texture = null;
        this.scissor = clip;
        this.bounds = bounds;
    }

    private void region(MapView view, int rx, int rz) {
        double west = (double) rx * Regions.SIZE;
        double north = (double) rz * Regions.SIZE;
        double east = west + Regions.SIZE;
        double south = north + Regions.SIZE;
        x0 = view.screenX(west, north);
        y0 = view.screenY(west, north);
        x1 = view.screenX(west, south);
        y1 = view.screenY(west, south);
        x2 = view.screenX(east, south);
        y2 = view.screenY(east, south);
        x3 = view.screenX(east, north);
        y3 = view.screenY(east, north);
    }

    /** An axis-aligned rectangle of one colour. */
    void fill(float left, float top, float right, float bottom, int argb, ScreenRectangle bounds) {
        x0 = left;
        y0 = top;
        x1 = left;
        y1 = bottom;
        x2 = right;
        y2 = bottom;
        x3 = right;
        y3 = top;
        this.color = argb;
        this.texture = null;
        this.scissor = null;
        this.bounds = bounds;
    }

    /** A square of one colour centred on a screen position: a marker. Clipped to {@code clip} if given. */
    void dot(float x, float y, float half, int argb, @Nullable ScreenRectangle clip, ScreenRectangle bounds) {
        fill(x - half, y - half, x + half, y + half, argb, bounds);
        this.scissor = clip;
    }

    /**
     * A small arrowhead at a screen position, pointing along the unit vector (dirX, dirY) in
     * screen space. Drawn as a quad with two coincident corners.
     */
    void arrow(float x, float y, float dirX, float dirY, float size, int argb, ScreenRectangle bounds) {
        float backX = x - dirX * size * 0.7f;
        float backY = y - dirY * size * 0.7f;
        // (sideX, sideY) is the direction turned a quarter clockwise on screen: to the arrow's right.
        float sideX = -dirY * size * 0.75f;
        float sideY = dirX * size * 0.75f;
        // Tip, back-left, back-right: the same turn direction as the page quads.
        x0 = x + dirX * size;
        y0 = y + dirY * size;
        x1 = backX - sideX;
        y1 = backY - sideY;
        x2 = backX + sideX;
        y2 = backY + sideY;
        x3 = x2;
        y3 = y2;
        this.color = argb;
        this.texture = null;
        this.scissor = null;
        this.bounds = bounds;
    }

    @Override
    public void buildVertices(VertexConsumer out) {
        if (texture != null) {
            out.addVertex(x0, y0, 0f).setUv(0f, 0f).setColor(color);
            out.addVertex(x1, y1, 0f).setUv(0f, 1f).setColor(color);
            out.addVertex(x2, y2, 0f).setUv(1f, 1f).setColor(color);
            out.addVertex(x3, y3, 0f).setUv(1f, 0f).setColor(color);
        } else {
            out.addVertex(x0, y0, 0f).setColor(color);
            out.addVertex(x1, y1, 0f).setColor(color);
            out.addVertex(x2, y2, 0f).setColor(color);
            out.addVertex(x3, y3, 0f).setColor(color);
        }
    }

    @Override
    public RenderPipeline pipeline() {
        return texture != null ? RenderPipelines.GUI_TEXTURED : RenderPipelines.GUI;
    }

    @Override
    public TextureSetup textureSetup() {
        return texture != null ? texture : TextureSetup.noTexture();
    }

    @Override
    public @Nullable ScreenRectangle scissorArea() {
        return scissor;
    }

    @Override
    public @Nullable ScreenRectangle bounds() {
        return bounds;
    }
}
