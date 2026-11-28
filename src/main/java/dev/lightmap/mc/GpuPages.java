package dev.lightmap.mc;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.SamplerCache;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.lightmap.core.DirtyTiles;
import dev.lightmap.core.PageStore;
import dev.lightmap.core.RegionPage;
import dev.lightmap.core.Regions;
import dev.lightmap.core.Stats;
import net.minecraft.client.gui.render.TextureSetup;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * The GPU side of the map pages. Client thread only.
 *
 * <p>Pages do not get a texture each. They share atlases: 2048x2048 textures cut into sixteen
 * 512x512 slots, fifteen for pages and one that holds a patch of plain white. Everything the map
 * draws from one atlas, pages and flat-coloured shapes alike, then uses the same texture, and the
 * GUI renderer, which starts a new draw call whenever the texture changes, draws it in one call
 * instead of one per region. Array textures would do the same job more simply, but the game's
 * GPU front end does not offer them.
 *
 * <p>After a page's first full upload, only the tiles the worker changed are sent, merged into as
 * few rectangles as possible and capped per tick. Uploads go through the game's ordinary
 * texture-write call, so they work the same on every backend.
 */
final class GpuPages {
    /** Slots along one side of an atlas. */
    static final int SLOTS_SIDE = 4;
    static final int ATLAS_SIZE = SLOTS_SIDE * Regions.SIZE;
    /** The last slot is not for a page: its first texels are the white patch. */
    static final int WHITE_SLOT = SLOTS_SIDE * SLOTS_SIDE - 1;
    static final int PAGES_PER_ATLAS = WHITE_SLOT;
    /** Side of the white patch in texels: wide enough that smooth sampling at its centre sees only white. */
    static final int WHITE_SIZE = 16;
    /** Texture coordinate, the same on both axes and in every atlas, of the middle of the white patch. */
    static final float WHITE_UV = ((WHITE_SLOT % SLOTS_SIDE) * Regions.SIZE + WHITE_SIZE / 2) / (float) ATLAS_SIZE;

    /** How far, in texels, texel-by-texel sampling stays away from a slot's edge. */
    static final float EDGE_GUARD = 1f / 64f;

    /** One shared texture and the two ways of sampling it. */
    static final class Atlas {
        final GpuTexture texture;
        final GpuTextureView view;
        final TextureSetup nearest;
        final TextureSetup linear;
        /** Bit n is set while page slot n is taken. */
        int taken;

        Atlas(GpuTexture texture, GpuTextureView view, TextureSetup nearest, TextureSetup linear) {
            this.texture = texture;
            this.view = view;
            this.nearest = nearest;
            this.linear = linear;
        }
    }

    /** A page's place in an atlas. */
    static final class Handle {
        final Atlas atlas;
        final int slot;
        /** The slot's top-left texel in the atlas. */
        final int originX;
        final int originZ;
        final GpuTexture texture;
        final TextureSetup nearest;
        final TextureSetup linear;
        /**
         * The slot's edges as texture coordinates, for sampling texel by texel. Pulled in by a
         * sliver (a 64th of a texel, far too little to shift the picture) so that rounding on
         * the GPU can never land a sample on the first texel of the slot next door.
         */
        final float u0, v0, u1, v1;
        /**
         * The same pulled in by half a texel, for smooth sampling: at the very edge of a slot,
         * smooth sampling would mix in the neighbouring slot, which is some other region.
         */
        final float su0, sv0, su1, sv1;
        /** False until the whole page has been uploaded once; the slot holds garbage before. */
        boolean ready;

        Handle(Atlas atlas, int slot) {
            this.atlas = atlas;
            this.slot = slot;
            this.originX = (slot % SLOTS_SIDE) * Regions.SIZE;
            this.originZ = (slot / SLOTS_SIDE) * Regions.SIZE;
            this.texture = atlas.texture;
            this.nearest = atlas.nearest;
            this.linear = atlas.linear;
            float size = ATLAS_SIZE;
            u0 = (originX + EDGE_GUARD) / size;
            v0 = (originZ + EDGE_GUARD) / size;
            u1 = (originX + Regions.SIZE - EDGE_GUARD) / size;
            v1 = (originZ + Regions.SIZE - EDGE_GUARD) / size;
            su0 = (originX + 0.5f) / size;
            sv0 = (originZ + 0.5f) / size;
            su1 = (originX + Regions.SIZE - 0.5f) / size;
            sv1 = (originZ + Regions.SIZE - 0.5f) / size;
        }
    }

    /** The atlases in use. A new page takes the first free slot, so the pages in use stay together. */
    private static final ArrayList<Atlas> ATLASES = new ArrayList<>();

    private static final int BYTES_PER_TILE = Regions.TILE * Regions.TILE * 4;

    // Scratch space, reused for every upload.
    private static final ByteBuffer STAGING = ByteBuffer.allocateDirect(Regions.AREA * 4).order(ByteOrder.LITTLE_ENDIAN);
    private static final IntBuffer STAGING_PIXELS = STAGING.asIntBuffer();
    private static final int[] ROWS = new int[Regions.TILES];
    private static final int[] LEFTOVER = new int[Regions.TILES];
    private static final int[] RECTS = new int[DirtyTiles.MAX_RECTS];

    private GpuPages() {
    }

    /**
     * Uploads waiting tiles until the byte budget is spent. At least one rectangle goes out per
     * call if anything is waiting, so a small budget slows uploads down but cannot stall them.
     */
    static void upload(PageStore store, Stats stats, int byteBudget) {
        int budget = byteBudget;
        long started = 0;
        RegionPage page;
        while (budget > 0 && (page = store.pollUpload()) != null) {
            if (page.removed) {
                continue;
            }
            if (started == 0) {
                started = System.nanoTime();
            }
            page.takeDirty(ROWS);
            Handle handle = (Handle) page.gpu;
            if (handle == null) {
                handle = create(stats);
                page.gpu = handle;
            }
            if (!handle.ready) {
                Arrays.fill(ROWS, -1);
            }

            int count = DirtyTiles.toRects(ROWS, RECTS);
            boolean leftover = false;
            for (int i = 0; i < count; i++) {
                int rect = RECTS[i];
                if (budget <= 0) {
                    if (!leftover) {
                        Arrays.fill(LEFTOVER, 0);
                        leftover = true;
                    }
                    DirtyTiles.set(LEFTOVER, rect);
                    continue;
                }
                int bytes = write(handle, page.pixels, rect);
                budget -= bytes;
                stats.clientUploadCalls++;
                stats.clientUploadBytes += bytes;
            }
            if (!leftover) {
                handle.ready = true;
            } else if (page.putBack(LEFTOVER)) {
                store.requeueUpload(page);
            }
        }
        if (started != 0) {
            stats.clientUploadNanos += System.nanoTime() - started;
        }
    }

    /** Gives the page's slot back. An atlas that no page uses any more is closed. */
    static void close(RegionPage page, Stats stats) {
        Handle handle = (Handle) page.gpu;
        if (handle != null) {
            page.gpu = null;
            Atlas atlas = handle.atlas;
            atlas.taken &= ~(1 << handle.slot);
            if (atlas.taken == 0) {
                ATLASES.remove(atlas);
                atlas.texture.close();
                atlas.view.close();
                stats.clientTexturesClosed++;
            }
        }
    }

    /** How many atlases are open. */
    static int atlases() {
        return ATLASES.size();
    }

    private static Handle create(Stats stats) {
        Atlas atlas = null;
        for (int i = 0; i < ATLASES.size(); i++) {
            if (ATLASES.get(i).taken != (1 << PAGES_PER_ATLAS) - 1) {
                atlas = ATLASES.get(i);
                break;
            }
        }
        if (atlas == null) {
            atlas = createAtlas();
            ATLASES.add(atlas);
            stats.clientTexturesCreated++;
        }
        int slot = Integer.numberOfTrailingZeros(~atlas.taken);
        atlas.taken |= 1 << slot;
        return new Handle(atlas, slot);
    }

    private static Atlas createAtlas() {
        GpuDevice device = RenderSystem.getDevice();
        int number = ATLASES.size();
        GpuTexture texture = device.createTexture(
                () -> "LightMap map atlas " + number,
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM, ATLAS_SIZE, ATLAS_SIZE, 1, 1);
        GpuTextureView view = device.createTextureView(texture);
        SamplerCache samplers = RenderSystem.getSamplerCache();
        // The white patch, which flat-coloured shapes sample so they can share the pages' draw.
        STAGING_PIXELS.clear();
        for (int i = 0; i < WHITE_SIZE * WHITE_SIZE; i++) {
            STAGING_PIXELS.put(-1);
        }
        STAGING.clear().limit(WHITE_SIZE * WHITE_SIZE * 4);
        int corner = (WHITE_SLOT % SLOTS_SIDE) * Regions.SIZE;
        device.createCommandEncoder().writeToTexture(texture, STAGING, 0, 0, corner, (WHITE_SLOT / SLOTS_SIDE) * Regions.SIZE, WHITE_SIZE, WHITE_SIZE);
        return new Atlas(texture, view,
                TextureSetup.singleTexture(view, samplers.getClampToEdge(FilterMode.NEAREST)),
                TextureSetup.singleTexture(view, samplers.getClampToEdge(FilterMode.LINEAR)));
    }

    /** Copies one rectangle of tiles out of the page and writes it into the page's slot. */
    private static int write(Handle handle, int[] pixels, int rect) {
        int x = DirtyTiles.x(rect) << Regions.TILE_SHIFT;
        int z = DirtyTiles.z(rect) << Regions.TILE_SHIFT;
        int w = DirtyTiles.w(rect) << Regions.TILE_SHIFT;
        int h = DirtyTiles.h(rect) << Regions.TILE_SHIFT;
        STAGING_PIXELS.clear();
        for (int row = 0; row < h; row++) {
            STAGING_PIXELS.put(pixels, ((z + row) << Regions.SHIFT) + x, w);
        }
        STAGING.clear().limit(w * h * 4);
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(handle.texture, STAGING, 0, 0, handle.originX + x, handle.originZ + z, w, h);
        return DirtyTiles.w(rect) * DirtyTiles.h(rect) * BYTES_PER_TILE;
    }
}
