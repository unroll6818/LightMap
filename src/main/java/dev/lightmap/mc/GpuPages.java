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
import java.util.Arrays;

/**
 * The GPU side of the map pages. Client thread only.
 *
 * <p>Each page that has anything to show gets one persistent 512x512 texture. After the first
 * full upload, only the tiles the worker changed are sent, merged into as few rectangles as
 * possible and capped per tick. Uploads go through the game's ordinary texture-write call, so
 * they work the same on every backend.
 */
final class GpuPages {
    /** A page's texture and the two ways of sampling it. */
    static final class Handle {
        final GpuTexture texture;
        final GpuTextureView view;
        final TextureSetup nearest;
        final TextureSetup linear;
        /** False until the whole page has been uploaded once; the texture holds garbage before. */
        boolean ready;

        Handle(GpuTexture texture, GpuTextureView view, TextureSetup nearest, TextureSetup linear) {
            this.texture = texture;
            this.view = view;
            this.nearest = nearest;
            this.linear = linear;
        }
    }

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
                handle = create(page);
                page.gpu = handle;
                stats.clientTexturesCreated++;
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
                int bytes = write(handle.texture, page.pixels, rect);
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

    static void close(RegionPage page, Stats stats) {
        Handle handle = (Handle) page.gpu;
        if (handle != null) {
            page.gpu = null;
            handle.texture.close();
            handle.view.close();
            stats.clientTexturesClosed++;
        }
    }

    private static Handle create(RegionPage page) {
        GpuDevice device = RenderSystem.getDevice();
        int rx = page.rx;
        int rz = page.rz;
        GpuTexture texture = device.createTexture(
                () -> "LightMap map page " + rx + "," + rz,
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM, Regions.SIZE, Regions.SIZE, 1, 1);
        GpuTextureView view = device.createTextureView(texture);
        SamplerCache samplers = RenderSystem.getSamplerCache();
        return new Handle(texture, view,
                TextureSetup.singleTexture(view, samplers.getClampToEdge(FilterMode.NEAREST)),
                TextureSetup.singleTexture(view, samplers.getClampToEdge(FilterMode.LINEAR)));
    }

    /** Copies one rectangle of tiles out of the page and hands it to the GPU. */
    private static int write(GpuTexture texture, int[] pixels, int rect) {
        int x = DirtyTiles.x(rect) << Regions.TILE_SHIFT;
        int z = DirtyTiles.z(rect) << Regions.TILE_SHIFT;
        int w = DirtyTiles.w(rect) << Regions.TILE_SHIFT;
        int h = DirtyTiles.h(rect) << Regions.TILE_SHIFT;
        STAGING_PIXELS.clear();
        for (int row = 0; row < h; row++) {
            STAGING_PIXELS.put(pixels, ((z + row) << Regions.SHIFT) + x, w);
        }
        STAGING.clear().limit(w * h * 4);
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, STAGING, 0, 0, x, z, w, h);
        return DirtyTiles.w(rect) * DirtyTiles.h(rect) * BYTES_PER_TILE;
    }
}
