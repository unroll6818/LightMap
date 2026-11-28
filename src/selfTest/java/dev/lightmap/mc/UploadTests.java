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

        // Two regions get their first chunks. A new page needs all of itself sent, 1 MB, so with a
        // 1 MB budget the two pages go out on two separate ticks. Both live in one atlas texture,
        // whose creation also writes its small white patch.
        int texturesBefore = gpu.textures.size();
        int writesBefore = gpu.writes;
        store.applyChunk(2, 3, chunk(stone, 70), SECOND);
        store.applyChunk(40, 3, chunk(sand, 64), SECOND);
        RegionPage a = store.pollEvent();
        RegionPage b = store.pollEvent();

        GpuPages.upload(store, stats, 1 << 20);
        Check.eq(1, gpu.textures.size() - texturesBefore, "one texture after the first tick");
        Check.eq(2, gpu.writes - writesBefore, "two write calls: the white patch and the page");
        Check.that(a.gpu instanceof GpuPages.Handle h && h.ready, "first page ready");
        Check.eq(null, b.gpu, "second page still waiting");
        GpuPages.upload(store, stats, 1 << 20);
        Check.eq(1, gpu.textures.size() - texturesBefore, "still one texture after the second tick: the pages share it");
        Check.eq(1, GpuPages.atlases(), "one atlas open");
        GpuPages.Handle ha = (GpuPages.Handle) a.gpu;
        GpuPages.Handle hb = (GpuPages.Handle) b.gpu;
        Check.that(ha.atlas == hb.atlas && ha.slot == 0 && hb.slot == 1, "first two slots of the same atlas");
        Check.that(ha.linear == hb.linear && ha.nearest == hb.nearest && ha.linear != ha.nearest, "pages on one atlas share its two texture setups");
        Check.that(hb.originX == Regions.SIZE && hb.originZ == 0, "slot 1 is the second square of the top row");
        Check.that(b.gpu instanceof GpuPages.Handle h && h.ready, "second page ready");
        same(gpu, a, "first page after its full upload");
        same(gpu, b, "second page after its full upload");
        FakeGpu.Texture texture = gpu.textureOf(a);
        Check.eq(GpuFormat.RGBA8_UNORM, texture.getFormat(), "texture format");
        Check.eq(GpuPages.ATLAS_SIZE, texture.getWidth(0), "texture width");
        Check.eq(2048, texture.getHeight(0), "texture height");

        // The white patch: sixteen texels square at the start of the last slot, and the texture
        // coordinate the flat-coloured quads use is its middle. Around it the texture is still junk.
        int patch = 3 * Regions.SIZE;
        int white = 0;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int at = ((patch + z) * 2048 + patch + x) * 4;
                if (texture.bytes[at] == -1 && texture.bytes[at + 1] == -1 && texture.bytes[at + 2] == -1 && texture.bytes[at + 3] == -1) {
                    white++;
                }
            }
        }
        Check.eq(256, white, "white patch is 16 by 16 opaque white texels");
        Check.eq((byte) 0x5A, texture.bytes[((patch + 16) * 2048 + patch) * 4], "nothing written below the patch");
        Check.eq((byte) 0x5A, texture.bytes[(patch * 2048 + patch + 16) * 4], "nor to its right");
        Check.near((patch + 8) / 2048.0, GpuPages.WHITE_UV, 1e-7, "the white coordinate is the middle of the patch");

        // Texture coordinates of a slot: its exact edges for texel-by-texel sampling, half a
        // texel inside them for smooth sampling so the neighbouring slot never mixes in.
        Check.near((512 + 1 / 64.0) / 2048.0, hb.u0, 1e-7, "slot 1 left edge, a sliver in");
        Check.near((1024 - 1 / 64.0) / 2048.0, hb.u1, 1e-7, "slot 1 right edge, a sliver in");
        Check.near((1 / 64.0) / 2048.0, hb.v0, 1e-7, "slot 1 top edge, a sliver in");
        Check.near((512 - 1 / 64.0) / 2048.0, hb.v1, 1e-7, "slot 1 bottom edge, a sliver in");
        Check.that(hb.u0 * 2048 > 512 && hb.u1 * 2048 < 1024 && hb.v0 > 0 && hb.v1 * 2048 < 512, "texel-by-texel sampling stays strictly inside the slot");
        Check.near(512.5 / 2048.0, hb.su0, 1e-7, "smooth: half a texel in from the left");
        Check.near(1023.5 / 2048.0, hb.su1, 1e-7, "smooth: half a texel in from the right");
        Check.near(0.5 / 2048.0, hb.sv0, 1e-7, "smooth: half a texel in from the top");
        Check.near(511.5 / 2048.0, hb.sv1, 1e-7, "smooth: half a texel in from the bottom");
        Check.eq(GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, texture.usage(), "texture usage");

        // Byte order: the texture's first byte of a pixel is red.
        int index = (3 * 16) * GpuPages.ATLAS_SIZE + 2 * 16;
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
        Check.eq(1L, stats.clientTexturesCreated, "one atlas created in total");
        Check.eq(1L, stats.clientTexturesClosed, "and closed with its last page");
        Check.eq(0, GpuPages.atlases(), "no atlas left open");

        slots(tables, gpu);
    }

    /** More pages than one atlas holds, and slots handed back and reused. */
    private static void slots(StateTables tables, FakeGpu gpu) {
        Stats stats = new Stats();
        PageStore store = new PageStore(null, 64, stats);
        store.open(tables.palette, tables);
        int stone = GameTests.id(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        int texturesBefore = gpu.textures.size();

        // Seventeen regions in a row, each with one chunk of a different height.
        List<RegionPage> pages = new ArrayList<>();
        for (int r = 0; r < 17; r++) {
            store.applyChunk(r * 32 + 1, 1, chunk(stone, 60 + r), SECOND);
            pages.add(store.pollEvent());
        }
        for (int tick = 0; tick < 40; tick++) {
            GpuPages.upload(store, stats, 4 << 20);
        }
        Check.eq(2, gpu.textures.size() - texturesBefore, "seventeen pages need two atlases");
        Check.eq(2, GpuPages.atlases(), "two atlases open");
        java.util.Set<Long> places = new java.util.HashSet<>();
        for (int r = 0; r < 17; r++) {
            GpuPages.Handle handle = (GpuPages.Handle) pages.get(r).gpu;
            Check.that(handle != null && handle.ready, "page " + r + " uploaded");
            Check.that(handle.slot >= 0 && handle.slot < GpuPages.PAGES_PER_ATLAS, "page " + r + " is in a page slot, not the white one");
            Check.that(places.add(((long) System.identityHashCode(handle.atlas) << 8) | handle.slot), "page " + r + " has a slot of its own");
            same(gpu, pages.get(r), "page " + r + " in its slot");
        }
        GpuPages.Handle first = (GpuPages.Handle) pages.get(0).gpu;
        GpuPages.Handle last = (GpuPages.Handle) pages.get(16).gpu;
        Check.that(((GpuPages.Handle) pages.get(14).gpu).atlas == first.atlas && ((GpuPages.Handle) pages.get(15).gpu).atlas == last.atlas,
                "the first fifteen fill the first atlas, the rest go to the second");
        Check.that(first.linear != last.linear, "different atlases are different textures");

        // A page that leaves gives its slot back, and the next new page takes it: the pages in
        // use stay together on the first atlas.
        GpuPages.close(pages.get(3), stats);
        Check.eq(null, pages.get(3).gpu, "closed page has no slot");
        store.applyChunk(40 * 32 + 1, 1, chunk(stone, 90), 2 * SECOND);
        RegionPage late = store.pollEvent();
        for (int tick = 0; tick < 10; tick++) {
            GpuPages.upload(store, stats, 4 << 20);
        }
        GpuPages.Handle reused = (GpuPages.Handle) late.gpu;
        Check.that(reused.atlas == first.atlas && reused.slot == 3 && reused.ready, "the freed slot is reused");
        same(gpu, late, "new page in the reused slot");
        same(gpu, pages.get(2), "its neighbour on one side is untouched");
        same(gpu, pages.get(4), "and on the other");

        // The second atlas closes as soon as its last page goes; the first stays while used.
        GpuPages.close(pages.get(15), stats);
        Check.eq(2, GpuPages.atlases(), "second atlas still has a page");
        GpuPages.close(pages.get(16), stats);
        Check.eq(1, GpuPages.atlases(), "second atlas closed with its last page");
        Check.that(((FakeGpu.Texture) last.texture).closed && !((FakeGpu.Texture) first.texture).closed, "only the empty one was closed");
        GpuPages.close(late, stats);
        for (int r = 0; r < 15; r++) {
            GpuPages.close(pages.get(r), stats);
        }
        Check.eq(0, GpuPages.atlases(), "no atlas left open");
        Check.eq(2L, stats.clientTexturesCreated, "two atlases created");
        Check.eq(2L, stats.clientTexturesClosed, "two closed");
        store.close();
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

    /** Whether the page's slot of its atlas holds exactly the page's pixels. */
    private static void same(FakeGpu gpu, RegionPage page, String what) {
        GpuPages.Handle handle = (GpuPages.Handle) page.gpu;
        byte[] bytes = gpu.textureOf(page).bytes;
        int wrong = 0;
        for (int z = 0; z < Regions.SIZE; z++) {
            for (int x = 0; x < Regions.SIZE; x++) {
                int p = page.pixels[(z << Regions.SHIFT) + x];
                int at = ((handle.originZ + z) * GpuPages.ATLAS_SIZE + handle.originX + x) * 4;
                if (bytes[at] != (byte) p || bytes[at + 1] != (byte) (p >> 8) || bytes[at + 2] != (byte) (p >> 16) || bytes[at + 3] != (byte) (p >>> 24)) {
                    wrong++;
                }
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
