package dev.lightmap.mc;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceLimits;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.api.device.HintsAndWorkarounds;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.backend.common.BaseGpuTexture;
import com.mojang.renderpearl.backend.common.BaseGpuTextureView;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import dev.lightmap.core.Check;
import dev.lightmap.core.ChunkColumns;
import dev.lightmap.core.PageStore;
import dev.lightmap.core.RegionPage;
import dev.lightmap.core.Regions;
import dev.lightmap.core.Stats;
import org.joml.Vector4fc;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Drives the texture upload code through the game's real GPU front end, which validates every
 * call, with a stand-in for the driver underneath that simply keeps the bytes it is given. That
 * shows the uploads are legal calls and that, once the queue is empty, each texture holds exactly
 * the page's picture, without needing a graphics card.
 */
final class UploadTests {
    private static final long SECOND = 1_000_000_000L;

    private UploadTests() {
    }

    static void run(StateTables tables) throws Exception {
        FakeGpu gpu = gpu();

        Stats stats = new Stats();
        PageStore store = new PageStore(null, 16, stats);
        store.open(tables.palette, tables);
        int stone = GameTests.id(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        int sand = GameTests.id(net.minecraft.world.level.block.Blocks.SAND.defaultBlockState());

        // Two regions get their first chunks. A new texture needs the whole page, 1 MB, so with a
        // 1 MB budget the two pages go out on two separate ticks.
        store.applyChunk(2, 3, chunk(stone, 70), SECOND);
        store.applyChunk(40, 3, chunk(sand, 64), SECOND);
        RegionPage a = store.pollEvent();
        RegionPage b = store.pollEvent();

        GpuPages.upload(store, stats, 1 << 20);
        Check.eq(1, gpu.textures.size(), "one texture after the first tick");
        Check.eq(1, gpu.writes, "one upload call");
        Check.that(a.gpu instanceof GpuPages.Handle h && h.ready, "first page ready");
        Check.eq(null, b.gpu, "second page still waiting");
        GpuPages.upload(store, stats, 1 << 20);
        Check.eq(2, gpu.textures.size(), "two textures after the second tick");
        Check.that(b.gpu instanceof GpuPages.Handle h && h.ready, "second page ready");
        same(gpu, a, "first page after its full upload");
        same(gpu, b, "second page after its full upload");
        FakeGpu.Texture texture = gpu.textureOf(a);
        Check.eq(GpuFormat.RGBA8_UNORM, texture.getFormat(), "texture format");
        Check.eq(Regions.SIZE, texture.getWidth(0), "texture width");
        Check.eq(GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, texture.usage(), "texture usage");

        // Byte order: the texture's first byte of a pixel is red.
        int index = ((3 * 16) << Regions.SHIFT) + 2 * 16;
        int rgb = tables.palette.rgb[stone];
        Check.that((texture.bytes[index * 4] & 0xFF) > 0 && texture.bytes[index * 4 + 3] == (byte) 0xFF, "pixel is opaque and coloured");
        Check.eq((rgb >> 16 & 0xFF) * 220 / 255, texture.bytes[index * 4] & 0xFF, "red byte first (level ground is shaded 220/255)");
        Check.eq((rgb & 0xFF) * 220 / 255, texture.bytes[index * 4 + 2] & 0xFF, "blue byte third");

        // Nothing queued: a tick with no changes makes no calls at all.
        int before = gpu.writes;
        GpuPages.upload(store, stats, 1 << 20);
        Check.eq(before, gpu.writes, "idle tick uploads nothing");

        // One changed chunk is one 16x16 upload.
        store.applyChunk(2, 3, chunk(sand, 70), 2 * SECOND);
        GpuPages.upload(store, stats, 1 << 20);
        Check.eq(before + 1, gpu.writes, "one call for one changed chunk");
        Check.that(Arrays.equals(new int[]{2 * 16, 3 * 16, 16, 16}, gpu.lastRect), "covering exactly that chunk");
        same(gpu, a, "page after a one-chunk update");

        // A strip of new chunks along one edge merges into a single rectangle.
        for (int cz = 8; cz < 24; cz++) {
            store.applyChunk(5, cz, chunk(stone, 60 + cz), 3 * SECOND);
        }
        before = gpu.writes;
        GpuPages.upload(store, stats, 1 << 20);
        Check.eq(before + 1, gpu.writes, "sixteen chunks in a column are one call");
        Check.that(Arrays.equals(new int[]{5 * 16, 8 * 16, 16, 16 * 16}, gpu.lastRect), "covering the strip");
        same(gpu, a, "page after the strip");

        // A scattered update larger than the budget is spread over ticks and still ends exact.
        // (Odd rows, even columns: no two of these chunks touch, and none sits right above a
        // chunk recorded earlier, so each is exactly one tile. One of them, 2,3, is already on
        // the map as sand; it turns to stone like the rest, so its picture changes too.)
        for (int cz = 1; cz < 32; cz += 2) {
            for (int cx = 0; cx < 32; cx += 2) {
                store.applyChunk(cx, cz, chunk(stone, 80 + (cx + cz) % 5), 4 * SECOND);
            }
        }
        int ticks = 0;
        long bytesBefore = stats.clientUploadBytes;
        before = gpu.writes;
        while (gpu.writes - before < 256 && ticks < 1000) {
            int callsBefore = gpu.writes;
            long tickBytes = stats.clientUploadBytes;
            GpuPages.upload(store, stats, 16 * 1024);
            ticks++;
            Check.that(gpu.writes > callsBefore, "every tick makes progress");
            Check.that(stats.clientUploadBytes - tickBytes <= 16 * 1024 + 1024, "and stays within the budget plus one rectangle");
        }
        Check.eq(256, gpu.writes - before, "256 scattered chunks are 256 calls");
        Check.eq(256L * 1024, stats.clientUploadBytes - bytesBefore, "of one chunk each");
        Check.that(ticks >= 16, "spread over several ticks (" + ticks + ")");
        Check.eq(null, store.pollUpload(), "queue empty afterwards");
        same(gpu, a, "page after the scattered update");

        // Dropping the pages closes their textures, and a dropped page is never uploaded to.
        store.applyChunk(40, 4, chunk(stone, 64), 5 * SECOND);
        store.close();
        before = gpu.writes;
        GpuPages.upload(store, stats, 1 << 20);
        Check.eq(before, gpu.writes, "removed page is skipped");
        for (RegionPage page; (page = store.pollEvent()) != null; ) {
            Check.that(page.removed, "page announced as removed");
            GpuPages.close(page, stats);
        }
        Check.that(gpu.textures.stream().allMatch(t -> t.closed), "all textures closed");
        Check.eq(2L, stats.clientTexturesCreated, "two textures created in total");
        Check.eq(2L, stats.clientTexturesClosed, "and two closed");
    }

    private static FakeGpu installed;

    /**
     * Installs the stand-in driver under the game's real front-end device, once. The front end is
     * created without its constructor, which would start the shader compiler (native code the map
     * never touches), and given the two fields the texture calls use.
     */
    static FakeGpu gpu() throws Exception {
        if (installed == null) {
            FakeGpu gpu = new FakeGpu();
            FrontendGpuDevice device = (FrontendGpuDevice) GameTests.unsafe().allocateInstance(FrontendGpuDevice.class);
            GameTests.set(FrontendGpuDevice.class, device, "backend", gpu);
            GameTests.set(FrontendGpuDevice.class, device, "encoder", new FrontendCommandEncoder(null, gpu, gpu));
            GameTests.set(RenderSystem.class, null, "DEVICE", device);
            RenderSystem.getSamplerCache().initialize();
            installed = gpu;
        }
        return installed;
    }

    private static ChunkColumns chunk(int state, int height) {
        ChunkColumns c = new ChunkColumns();
        Arrays.fill(c.state, state);
        Arrays.fill(c.height, (short) height);
        return c;
    }

    /** The texture holds the page's pixels, byte for byte, in R, G, B, A order. */
    private static void same(FakeGpu gpu, RegionPage page, String what) {
        byte[] bytes = gpu.textureOf(page).bytes;
        int wrong = 0;
        for (int i = 0; i < Regions.AREA; i++) {
            int p = page.pixels[i];
            if (bytes[i * 4] != (byte) p || bytes[i * 4 + 1] != (byte) (p >> 8) || bytes[i * 4 + 2] != (byte) (p >> 16) || bytes[i * 4 + 3] != (byte) (p >>> 24)) {
                wrong++;
            }
        }
        Check.eq(0, wrong, "wrong pixels in texture, " + what);
    }

    /** Stands in for a graphics driver: remembers textures and the bytes written to them. */
    static final class FakeGpu implements GpuDeviceBackend, CommandEncoderBackend {
        final List<Texture> textures = new ArrayList<>();
        int writes;
        int[] lastRect;

        static final class Texture extends BaseGpuTexture {
            final byte[] bytes;
            boolean closed;

            Texture(int usage, String label, GpuFormat format, int width, int height) {
                super(usage, label, format, width, height, 1, 1);
                bytes = new byte[width * height * 4];
                // Like real video memory, a new texture starts with junk.
                Arrays.fill(bytes, (byte) 0x5A);
            }

            @Override
            public void close() {
                closed = true;
            }

            @Override
            public boolean isClosed() {
                return closed;
            }
        }

        static final class View extends BaseGpuTextureView {
            boolean closed;

            View(GpuTexture texture, int baseMipLevel, int mipLevels) {
                super(texture, baseMipLevel, mipLevels);
            }

            @Override
            public void close() {
                closed = true;
            }

            @Override
            public boolean isClosed() {
                return closed;
            }
        }

        record Sampler(AddressMode getAddressModeU, AddressMode getAddressModeV, FilterMode getMinFilter, FilterMode getMagFilter,
                       int getMaxAnisotropy, OptionalDouble getMaxLod) implements GpuSampler {
            @Override
            public void close() {
            }

            @Override
            public boolean isClosed() {
                return false;
            }
        }

        Texture textureOf(RegionPage page) {
            return (Texture) ((GpuPages.Handle) page.gpu).texture;
        }

        @Override
        public GpuTexture createTexture(String label, int usage, GpuFormat format, int width, int height, int depthOrLayers, int mipLevels) {
            Texture texture = new Texture(usage, label == null ? "" : label, format, width, height);
            textures.add(texture);
            return texture;
        }

        @Override
        public GpuTextureView createTextureView(GpuTexture texture, int baseMipLevel, int mipLevels) {
            return new View(texture, baseMipLevel, mipLevels);
        }

        @Override
        public GpuSampler createSampler(AddressMode u, AddressMode v, FilterMode min, FilterMode mag, int maxAnisotropy, OptionalDouble maxLod) {
            return new Sampler(u, v, min, mag, maxAnisotropy, maxLod);
        }

        @Override
        public CommandEncoderBackend createCommandEncoder() {
            return this;
        }

        @Override
        public void writeToTexture(GpuTexture destination, ByteBuffer source, int mipLevel, int depthOrLayer, int destX, int destY, int width, int height) {
            Texture texture = (Texture) destination;
            if (texture.closed) {
                throw new IllegalStateException("write to a closed texture");
            }
            if (source.remaining() != width * height * 4) {
                throw new IllegalStateException("source holds " + source.remaining() + " bytes for a " + width + "x" + height + " write");
            }
            ByteBuffer data = source.duplicate();
            for (int row = 0; row < height; row++) {
                data.get(texture.bytes, ((destY + row) * texture.getWidth(0) + destX) * 4, width * 4);
            }
            writes++;
            lastRect = new int[]{destX, destY, width, height};
        }

        @Override
        public boolean isDebuggingEnabled() {
            return false;
        }

        // Nothing below is reached by the map; a call would be a bug worth hearing about.

        @Override
        public GpuSurfaceBackend createSurface(long windowHandle, BooleanSupplier isIconified) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GpuBuffer createBuffer(Supplier<String> label, int usage, long size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GpuBuffer createBuffer(Supplier<String> label, int usage, ByteBuffer data) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> getLastDebugMessages() {
            return List.of();
        }

        @Override
        public BackendRenderPipeline.Pending compilePipeline(BackendRenderPipeline.CreateInfo pipelineCreateInfo) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }

        @Override
        public GpuQueryPool createTimestampQueryPool(int size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getTimestampCalibrationOffset() {
            return 0;
        }

        @Override
        public DeviceInfo getDeviceInfo() {
            return new DeviceInfo("stand-in", "test", "none", true, "test", 1f,
                    new DeviceLimits(16, 256, 16384, 1L << 30, 1024, 8, 1024),
                    new DeviceFeatures(false, false, false, false, false, false, false, false),
                    Set.of(), new HintsAndWorkarounds(false, false, false, false), DeviceType.OTHER);
        }

        @Override
        public void submit() {
            throw new UnsupportedOperationException();
        }

        @Override
        public TransientMemory transientMemory() {
            throw new UnsupportedOperationException();
        }

        @Override
        public RenderPassBackend createRenderPass(RenderPassDescriptor descriptor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void submitRenderPass() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clearColorTexture(GpuTexture colorTexture, Vector4fc clearColor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clearColorAndDepthTextures(GpuTexture colorTexture, Vector4fc clearColor, GpuTexture depthTexture, double clearDepth) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clearColorAndDepthTextures(GpuTexture colorTexture, Vector4fc clearColor, GpuTexture depthTexture, double clearDepth,
                                               int regionX, int regionY, int regionWidth, int regionHeight, int mipLevel) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clearDepthTexture(GpuTexture depthTexture, double clearDepth) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void writeToBuffer(GpuBufferSlice destination, ByteBuffer data) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice target) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void copyBufferToTexture(GpuBufferSlice source, int sourceX, int sourceY, int sourceWidth, int sourceHeight, GpuTexture destination,
                                        int destinationX, int destinationY, int copyWidth, int copyHeight, int mipLevel, int arrayLayer) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void copyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel, int x, int y, int width, int height) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void copyTextureToTexture(GpuTexture source, GpuTexture destination, int mipLevel, int destX, int destY, int sourceX, int sourceY, int width, int height) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GpuFence createFence() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void writeTimestamp(GpuQueryPool pool, int index) {
            throw new UnsupportedOperationException();
        }
    }
}
