package dev.lightmap.core;

import dev.lightmap.Settings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Self-tests for everything that does not need the game: page bookkeeping, colouring, dirty
 * tracking, the file format and the worker. Run with {@code gradlew selfTest}.
 */
public final class CoreTests {
    static final int AIR = 0;
    static final int STONE = 1;
    static final int GRASS = 2;
    static final int WATER = 3;
    static final int SAND = 4;
    static final int GLASS = 5;

    static final long SECOND = 1_000_000_000L;

    public static void main(String[] args) {
        Check.run("region keys round-trip, including negatives", CoreTests::regionKeys);
        Check.run("dirty tiles: simple shapes give few rectangles", CoreTests::dirtyShapes);
        Check.run("dirty tiles: rectangles cover exactly the dirty set", CoreTests::dirtyFuzz);
        Check.run("shading: land relief, fluid depth, unexplored and void", CoreTests::shading);
        Check.run("view: heading-up rotation and inverse", CoreTests::view);
        Check.run("store: a chunk marks its tile, an unchanged chunk marks nothing", CoreTests::applyChunk);
        Check.run("store: relief is continuous across chunk and region borders", CoreTests::reliefAcrossBorders);
        Check.run("store: tiles that miss the upload budget are queued again", CoreTests::putBack);
        Check.run("codec: round-trip keeps every recorded column", CoreTests::codecRoundTrip);
        Check.run("codec: rejects foreign, mislabelled and truncated data", CoreTests::codecRejects);
        Check.run("store: saved regions come back with identical pixels", CoreTests::saveAndReload);
        Check.run("store: idle pages are saved, then dropped, and reload on demand", CoreTests::eviction);
        Check.run("store: page count stays under the cap", CoreTests::pageCap);
        Check.run("store: memory-only mode never touches the disk", CoreTests::memoryOnly);
        Check.run("worker: tasks run in order and upkeep runs between them", CoreTests::worker);
        Check.run("settings: defaults, clamping, save and reload, broken files", CoreTests::settings);
        Check.run("markers: add, change, remove, file round-trip and broken lines", CoreTests::markers);
        Check.finish();
    }

    // ------------------------------------------------------------------ fixtures

    static Palette palette() {
        int[] rgb = new int[6];
        byte[] flags = new byte[6];
        rgb[STONE] = 0x707070;
        flags[STONE] = Palette.VISIBLE;
        rgb[GRASS] = 0x7FB238;
        flags[GRASS] = Palette.VISIBLE;
        rgb[WATER] = 0x4040FF;
        flags[WATER] = Palette.VISIBLE | Palette.FLUID | Palette.HAS_FLUID;
        rgb[SAND] = 0xF7E9A3;
        flags[SAND] = Palette.VISIBLE;
        return new Palette(rgb, flags);
    }

    static final RegionCodec.StateNames NAMES = new RegionCodec.StateNames() {
        @Override
        public String nameOf(int stateId) {
            return "test:state_" + stateId;
        }

        @Override
        public int idOf(String name) {
            if (!name.startsWith("test:state_")) {
                return 0;
            }
            int id = Integer.parseInt(name.substring("test:state_".length()));
            return id < 6 ? id : 0;
        }
    };

    static PageStore store(Path dir, int maxPages) {
        PageStore store = new PageStore(dir == null ? null : new RegionFiles(dir), maxPages, new Stats());
        store.open(palette(), NAMES);
        return store;
    }

    /** A chunk of one block state on a plane that rises one block per block towards the south. */
    static ChunkColumns slope(int cz, int state, int baseHeight) {
        ChunkColumns c = new ChunkColumns();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                c.state[z << 4 | x] = state;
                c.height[z << 4 | x] = (short) (baseHeight + cz * 16 + z);
            }
        }
        return c;
    }

    static ChunkColumns flat(int state, int height, int depth) {
        ChunkColumns c = new ChunkColumns();
        Arrays.fill(c.state, state);
        Arrays.fill(c.height, (short) height);
        Arrays.fill(c.depth, (byte) depth);
        return c;
    }

    static List<RegionPage> drainUploads(PageStore store) {
        List<RegionPage> pages = new ArrayList<>();
        for (RegionPage p; (p = store.pollUpload()) != null; ) {
            pages.add(p);
        }
        return pages;
    }

    static int dirtyCount(RegionPage page) {
        int[] rows = new int[Regions.TILES];
        page.takeDirty(rows);
        int n = 0;
        for (int r : rows) {
            n += Integer.bitCount(r);
        }
        return n;
    }

    static int pixel(RegionPage page, int x, int z) {
        return page.pixels[(z << Regions.SHIFT) + x];
    }

    static int brightness(int abgr) {
        return (abgr & 0xFF) + (abgr >> 8 & 0xFF) + (abgr >> 16 & 0xFF);
    }

    static Path tempDir() throws IOException {
        return Files.createTempDirectory("lightmap-test");
    }

    // ------------------------------------------------------------------ tests

    static void regionKeys() {
        int[] samples = {0, 1, -1, 31, -32, 58593, -58594, Integer.MAX_VALUE, Integer.MIN_VALUE};
        for (int x : samples) {
            for (int z : samples) {
                long key = Regions.key(x, z);
                Check.eq(x, Regions.keyX(key), "keyX");
                Check.eq(z, Regions.keyZ(key), "keyZ");
            }
        }
        Check.eq(0, Regions.ofBlock(511), "block 511");
        Check.eq(1, Regions.ofBlock(512), "block 512");
        Check.eq(-1, Regions.ofBlock(-1), "block -1");
        Check.eq(-1, Regions.ofBlock(-512), "block -512");
        Check.eq(-2, Regions.ofBlock(-513), "block -513");
        Check.eq(0, Regions.ofChunk(31), "chunk 31");
        Check.eq(-1, Regions.ofChunk(-1), "chunk -1");
        Check.eq(-2, Regions.ofChunk(-33), "chunk -33");
    }

    static void dirtyShapes() {
        int[] out = new int[DirtyTiles.MAX_RECTS];

        int[] full = new int[Regions.TILES];
        Arrays.fill(full, -1);
        Check.eq(1, DirtyTiles.toRects(full, out), "full page");
        Check.eq(DirtyTiles.pack(0, 0, 32, 32), out[0], "full page rectangle");

        int[] strip = new int[Regions.TILES];
        for (int z = 0; z < 32; z++) {
            strip[z] = 1 << 7;
        }
        Check.eq(1, DirtyTiles.toRects(strip, out), "one column of chunks");
        Check.eq(DirtyTiles.pack(7, 0, 1, 32), out[0], "column rectangle");

        int[] ring = new int[Regions.TILES];
        for (int z = 4; z <= 20; z++) {
            ring[z] = z == 4 || z == 20 ? ((1 << 17) - 1) << 4 : 1 << 4 | 1 << 20;
        }
        Check.eq(4, DirtyTiles.toRects(ring, out), "ring of chunks");

        Check.eq(0, DirtyTiles.toRects(new int[Regions.TILES], out), "nothing dirty");

        int[] single = new int[Regions.TILES];
        single[31] = 1 << 31;
        Check.eq(1, DirtyTiles.toRects(single, out), "last tile");
        Check.eq(DirtyTiles.pack(31, 31, 1, 1), out[0], "last tile rectangle");
    }

    static void dirtyFuzz() {
        Random random = new Random(42);
        int[] out = new int[DirtyTiles.MAX_RECTS];
        for (int round = 0; round < 500; round++) {
            int[] rows = new int[Regions.TILES];
            int density = random.nextInt(5);
            for (int z = 0; z < 32; z++) {
                rows[z] = switch (density) {
                    case 0 -> random.nextInt() & random.nextInt() & random.nextInt();
                    case 1 -> random.nextInt();
                    case 2 -> random.nextInt() | random.nextInt();
                    case 3 -> random.nextBoolean() ? -1 : 0;
                    default -> 0x55555555 << (z & 1);
                };
            }
            int n = DirtyTiles.toRects(rows, out);
            int[] rebuilt = new int[Regions.TILES];
            int area = 0;
            for (int i = 0; i < n; i++) {
                int r = out[i];
                Check.that(DirtyTiles.w(r) >= 1 && DirtyTiles.h(r) >= 1, "rectangle has area");
                Check.that(DirtyTiles.x(r) + DirtyTiles.w(r) <= 32 && DirtyTiles.z(r) + DirtyTiles.h(r) <= 32, "rectangle inside page");
                area += DirtyTiles.w(r) * DirtyTiles.h(r);
                DirtyTiles.set(rebuilt, r);
            }
            int bits = 0;
            for (int row : rows) {
                bits += Integer.bitCount(row);
            }
            Check.that(Arrays.equals(rows, rebuilt), "rectangles reproduce the bitmap (round " + round + ")");
            Check.eq(bits, area, "rectangles do not overlap (round " + round + ")");
        }
    }

    static void shading() {
        Palette p = palette();
        Check.eq(Shading.UNEXPLORED, Shading.pixel(p, GRASS, RegionPage.NO_DATA, 0, (short) 64), "no data");
        Check.eq(Shading.VOID, Shading.pixel(p, AIR, (short) 64, 0, (short) 64), "air column");
        Check.eq(Shading.VOID, Shading.pixel(p, GLASS, (short) 64, 0, (short) 64), "invisible state");
        Check.eq(Shading.VOID, Shading.pixel(p, 999, (short) 64, 0, (short) 64), "unknown state id");

        int level = Shading.pixel(p, GRASS, (short) 64, 0, (short) 64);
        int up = Shading.pixel(p, GRASS, (short) 66, 0, (short) 64);
        int down = Shading.pixel(p, GRASS, (short) 62, 0, (short) 64);
        int cliff = Shading.pixel(p, GRASS, (short) 200, 0, (short) 64);
        int noNorth = Shading.pixel(p, GRASS, (short) 64, 0, RegionPage.NO_DATA);
        Check.that(brightness(up) > brightness(level), "rising ground is lighter");
        Check.that(brightness(down) < brightness(level), "falling ground is darker");
        Check.that(brightness(cliff) >= brightness(up), "relief saturates rather than wrapping");
        Check.eq(level, noNorth, "missing northern neighbour reads as level ground");
        Check.eq(0xFF, level >>> 24, "pixels are opaque");
        // Full brightness reproduces the palette colour exactly, in R,G,B,A byte order.
        Check.eq(0xFF000000 | 0x38 << 16 | 0xB2 << 8 | 0x7F, cliff, "ABGR packing of 0x7FB238");

        int shallow = Shading.pixel(p, WATER, (short) 62, 1, (short) 80);
        int deeper = Shading.pixel(p, WATER, (short) 62, 8, (short) 80);
        int abyss = Shading.pixel(p, WATER, (short) 62, 200, (short) 80);
        int abyss2 = Shading.pixel(p, WATER, (short) 62, 255, (short) 20);
        Check.that(brightness(shallow) > brightness(deeper), "deeper water is darker");
        Check.that(brightness(deeper) > brightness(abyss), "and darker still");
        Check.eq(abyss, abyss2, "fluid shade ignores slope and levels off");
    }

    static void view() {
        MapView v = new MapView();
        // Facing south: south of the player is up the screen, east is to the left.
        v.set(1000.5, -2000.5, 100, 100, 0.5f, MapView.headingUp(0));
        Check.near(100, v.screenX(1000.5, -2000.5), 1e-4, "origin x");
        Check.near(100, v.screenY(1000.5, -2000.5), 1e-4, "origin y");
        Check.near(100, v.screenX(1000.5, -1980.5), 1e-3, "south x");
        Check.near(90, v.screenY(1000.5, -1980.5), 1e-3, "south is up");
        Check.near(90, v.screenX(1020.5, -2000.5), 1e-3, "east is left");

        // Facing east: east is up, south is to the right.
        v.set(0, 0, 0, 0, 2f, MapView.headingUp(-90));
        Check.near(0, v.screenX(10, 0), 1e-3, "east x");
        Check.near(-20, v.screenY(10, 0), 1e-3, "east is up");
        Check.near(20, v.screenX(0, 10), 1e-3, "south is right");

        // North up, no rotation: +x right, +z down.
        v.set(0, 0, 0, 0, 1f, 0);
        Check.near(5, v.screenX(5, 7), 1e-4, "north-up x");
        Check.near(7, v.screenY(5, 7), 1e-4, "north-up y");

        // Far from the origin the transform still resolves single blocks.
        v.set(29_999_000.25, -29_999_000.75, 50, 60, 1.5f, 0.7f);
        float sx = v.screenX(29_999_010.25, -29_998_990.75);
        float sy = v.screenY(29_999_010.25, -29_998_990.75);
        Check.near(29_999_010.25, v.worldX(sx, sy), 1e-3, "inverse x at the world edge");
        Check.near(-29_998_990.75, v.worldZ(sx, sy), 1e-3, "inverse z at the world edge");
        Check.near(Math.sqrt(2) * 50 / 1.5, v.coverRadius(100, 100), 1e-6, "cover radius");
    }

    static void applyChunk() {
        PageStore store = store(null, 16);
        store.applyChunk(3, 5, flat(GRASS, 64, 0), SECOND);

        RegionPage added = store.pollEvent();
        Check.that(added != null && !added.removed && added.rx == 0 && added.rz == 0, "page 0,0 announced");
        Check.eq(null, store.pollEvent(), "one event only");

        List<RegionPage> uploads = drainUploads(store);
        Check.eq(1, uploads.size(), "page queued once");
        int[] rows = new int[Regions.TILES];
        uploads.get(0).takeDirty(rows);
        Check.eq(1 << 3, rows[5], "tile 3,5 dirty");
        rows[5] = 0;
        Check.that(Arrays.stream(rows).allMatch(r -> r == 0), "no other tile dirty");

        RegionPage page = store.pageIfLoaded(0, 0);
        Check.that(page.hasChunk(3, 5), "chunk present");
        Check.that(!page.hasChunk(4, 5), "neighbour absent");
        Check.eq(Shading.UNEXPLORED, pixel(page, 0, 0), "untouched pixel");
        Check.that(pixel(page, 3 * 16, 5 * 16) != Shading.UNEXPLORED, "chunk pixel coloured");
        Check.eq(Shading.UNEXPLORED, pixel(page, 3 * 16, 6 * 16), "row below stays unexplored");

        store.applyChunk(3, 5, flat(GRASS, 64, 0), 2 * SECOND);
        Check.eq(0, drainUploads(store).size(), "unchanged chunk queues nothing");
        Check.eq(1L, store.stats.workerChunksUnchanged, "counted as unchanged");

        store.applyChunk(3, 5, flat(SAND, 64, 0), 3 * SECOND);
        Check.eq(1, drainUploads(store).size(), "changed chunk queues the page again");

        // Negative coordinates land in the right page and tile.
        store.applyChunk(-1, -33, flat(STONE, 10, 0), 4 * SECOND);
        RegionPage west = store.pageIfLoaded(-1, -2);
        Check.that(west != null && west.hasChunk(31, 31), "chunk -1,-33 is tile 31,31 of region -1,-2");
    }

    static void reliefAcrossBorders() {
        PageStore store = store(null, 16);
        // Ground rising towards the south, crossing from region z=0 into z=1 at chunk 32.
        for (int cz = 29; cz <= 34; cz++) {
            store.applyChunk(0, cz, slope(cz, GRASS, 0), SECOND);
        }
        RegionPage north = store.pageIfLoaded(0, 0);
        RegionPage south = store.pageIfLoaded(0, 1);
        int lit = pixel(north, 5, 30 * 16 + 5);
        Check.eq(lit, pixel(north, 5, 31 * 16), "first row of a chunk shades against the chunk above");
        Check.eq(lit, pixel(south, 5, 0), "first row of a region shades against the region above");
        Check.eq(lit, pixel(south, 5, 16), "and the next chunk down");
        // The very first row recorded has nothing to its north and reads as level ground.
        Check.that(brightness(pixel(north, 5, 29 * 16)) < brightness(lit), "top edge of the recorded area is not lit");

        // Recording in the other order must end with the same picture.
        PageStore reversed = store(null, 16);
        for (int cz = 34; cz >= 29; cz--) {
            reversed.applyChunk(0, cz, slope(cz, GRASS, 0), SECOND);
        }
        Check.that(Arrays.equals(north.pixels, reversed.pageIfLoaded(0, 0).pixels), "north page independent of order");
        Check.that(Arrays.equals(south.pixels, reversed.pageIfLoaded(0, 1).pixels), "south page independent of order");

        // Lowering a chunk re-shades the row below it and queues that tile too.
        drainUploads(store).forEach(CoreTests::dirtyCount);
        store.applyChunk(0, 31, flat(GRASS, -60, 0), 2 * SECOND);
        List<RegionPage> uploads = drainUploads(store);
        Check.eq(2, uploads.size(), "both pages queued");
        Check.that(brightness(pixel(south, 5, 0)) > brightness(pixel(south, 5, 1)), "row below re-shaded as a rise");
        Check.eq(1, dirtyCount(south), "one tile of the south page");
        Check.eq(1, dirtyCount(north), "one tile of the north page");
    }

    static void putBack() {
        PageStore store = store(null, 16);
        store.applyChunk(0, 0, flat(GRASS, 64, 0), SECOND);
        store.applyChunk(1, 0, flat(GRASS, 64, 0), SECOND);
        RegionPage page = store.pollUpload();
        Check.eq(null, store.pollUpload(), "queued once for two tiles");

        int[] rows = new int[Regions.TILES];
        page.takeDirty(rows);
        Check.eq(0b11, rows[0], "two tiles taken");

        // Tiles marked while the uploader holds the set must queue the page again.
        store.applyChunk(2, 0, flat(GRASS, 64, 0), SECOND);
        Check.that(store.pollUpload() == page, "new tile queues the page again");

        int[] leftover = new int[Regions.TILES];
        leftover[0] = 0b10;
        Check.that(!page.putBack(leftover), "already-queued page is not queued twice");
        page.takeDirty(rows);
        Check.eq(0b110, rows[0], "leftover merged with new work");
        Check.that(page.putBack(leftover), "idle page asks to be queued");
        Check.that(!page.putBack(new int[Regions.TILES]), "nothing to put back, nothing to queue");
    }

    static void codecRoundTrip() throws IOException {
        Random random = new Random(7);
        RegionPage page = new RegionPage(-3, 12);
        for (int tz = 0; tz < Regions.TILES; tz++) {
            for (int tx = 0; tx < Regions.TILES; tx++) {
                if (random.nextInt(3) == 0) {
                    continue;
                }
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int i = ((tz * 16 + z) << Regions.SHIFT) + tx * 16 + x;
                        page.state[i] = random.nextInt(6);
                        page.height[i] = (short) (random.nextInt(4064) - 2032);
                        page.depth[i] = (byte) random.nextInt(256);
                    }
                }
            }
        }
        byte[] data = RegionCodec.encode(page, NAMES);
        RegionPage back = new RegionPage(-3, 12);
        Check.that(RegionCodec.decode(data, back, NAMES), "decodes");
        Check.that(Arrays.equals(page.state, back.state), "states");
        Check.that(Arrays.equals(page.height, back.height), "heights");
        Check.that(Arrays.equals(page.depth, back.depth), "depths");

        // An empty page is a valid, tiny file.
        byte[] empty = RegionCodec.encode(new RegionPage(0, 0), NAMES);
        RegionPage emptyBack = new RegionPage(0, 0);
        Check.that(RegionCodec.decode(empty, emptyBack, NAMES), "empty page decodes");
        Check.that(empty.length < 256, "empty page is small (" + empty.length + " bytes)");
        Check.that(!emptyBack.hasChunk(0, 0), "and stays empty");

        // A state this game does not know becomes air rather than an error.
        RegionCodec.StateNames forgetful = new RegionCodec.StateNames() {
            @Override
            public String nameOf(int stateId) {
                return NAMES.nameOf(stateId);
            }

            @Override
            public int idOf(String name) {
                return name.endsWith("_4") ? 0 : NAMES.idOf(name);
            }
        };
        RegionPage lossy = new RegionPage(-3, 12);
        Check.that(RegionCodec.decode(data, lossy, forgetful), "decodes with an unknown name");
        boolean sandGone = true;
        boolean restSame = true;
        for (int i = 0; i < Regions.AREA; i++) {
            if (page.state[i] == SAND) {
                sandGone &= lossy.state[i] == 0;
            } else {
                restSame &= lossy.state[i] == page.state[i];
            }
        }
        Check.that(sandGone && restSame, "only the unknown state is replaced");
    }

    static void codecRejects() throws IOException {
        RegionPage page = new RegionPage(1, 2);
        Arrays.fill(page.height, (short) 70);
        Arrays.fill(page.state, GRASS);
        byte[] data = RegionCodec.encode(page, NAMES);
        Check.that(data.length < 64 * 1024, "uniform region compresses well (" + data.length + " bytes)");

        Check.that(!RegionCodec.decode(data, new RegionPage(2, 1), NAMES), "wrong region coordinates");
        Check.that(!RegionCodec.decode(new byte[4], new RegionPage(1, 2), NAMES), "too short");

        byte[] foreign = data.clone();
        foreign[0] ^= 0x55;
        Check.that(!RegionCodec.decode(foreign, new RegionPage(1, 2), NAMES), "wrong magic");

        byte[] future = data.clone();
        future[5] = 99;
        Check.that(!RegionCodec.decode(future, new RegionPage(1, 2), NAMES), "unknown version");

        RegionPage target = new RegionPage(1, 2);
        boolean threw = false;
        try {
            RegionCodec.decode(Arrays.copyOf(data, data.length / 2), target, NAMES);
        } catch (IOException e) {
            threw = true;
        }
        Check.that(threw, "truncated data is an error");
        Check.that(!target.hasChunk(0, 0) && target.state[0] == 0, "and leaves the page untouched");
    }

    static void saveAndReload() throws IOException {
        Path dir = tempDir();
        PageStore first = store(dir, 16);
        Random random = new Random(3);
        for (int cz = 28; cz < 36; cz++) {
            for (int cx = -2; cx < 2; cx++) {
                ChunkColumns c = new ChunkColumns();
                for (int i = 0; i < 256; i++) {
                    boolean wet = random.nextInt(4) == 0;
                    c.state[i] = wet ? WATER : 1 + random.nextInt(2);
                    c.height[i] = (short) (60 + random.nextInt(8));
                    c.depth[i] = (byte) (wet ? 1 + random.nextInt(20) : 0);
                }
                first.applyChunk(cx, cz, c, SECOND);
            }
        }
        Check.eq(0, first.diskIndex().length, "nothing on disk before the first save");
        first.flush();
        Check.eq(4, first.diskIndex().length, "four regions saved");
        Check.eq(4L, first.stats.workerPagesSaved, "four saves counted");
        Check.eq(4, new RegionFiles(dir).list().length, "four files listed");

        PageStore second = store(dir, 16);
        Check.eq(4, second.diskIndex().length, "index read on open");
        for (long key : second.diskIndex()) {
            second.loadForDisplay(key, SECOND);
        }
        Check.eq(4L, second.stats.workerPagesLoaded, "four regions loaded");
        for (int rz = 0; rz <= 1; rz++) {
            for (int rx = -1; rx <= 0; rx++) {
                RegionPage a = first.pageIfLoaded(rx, rz);
                RegionPage b = second.pageIfLoaded(rx, rz);
                Check.that(Arrays.equals(a.state, b.state) && Arrays.equals(a.height, b.height) && Arrays.equals(a.depth, b.depth),
                        "raw layers of " + rx + "," + rz);
                Check.that(Arrays.equals(a.pixels, b.pixels), "pixels of " + rx + "," + rz);
            }
        }
        // A loaded page is queued in full: the uploader has to fill a new texture.
        for (RegionPage p : drainUploads(second)) {
            Check.eq(Regions.TILES * Regions.TILES, dirtyCount(p), "all tiles of a loaded page");
        }

        // Asking for a region that was never saved creates nothing.
        second.loadForDisplay(Regions.key(40, 40), SECOND);
        Check.eq(null, second.pageIfLoaded(40, 40), "unsaved region is not conjured up");

        // A corrupt file is skipped, and the region can be recorded afresh.
        System.out.println("  (a warning about an unreadable region 7,7 is expected here)");
        Files.write(dir.resolve("r.7.7.lmap"), new byte[]{1, 2, 3});
        PageStore third = store(dir, 16);
        third.applyChunk(7 * 32, 7 * 32, flat(SAND, 64, 0), SECOND);
        Check.that(third.pageIfLoaded(7, 7).hasChunk(0, 0), "corrupt region replaced by new data");
        third.close();
        PageStore fourth = store(dir, 16);
        fourth.loadForDisplay(Regions.key(7, 7), SECOND);
        Check.that(fourth.pageIfLoaded(7, 7) != null && fourth.pageIfLoaded(7, 7).state[0] == SAND, "and saved over");
    }

    static void eviction() throws IOException {
        Path dir = tempDir();
        PageStore store = store(dir, 16);
        store.applyChunk(0, 0, flat(GRASS, 64, 0), SECOND);
        RegionPage page = store.pollEvent();

        Check.that(!store.idle(SECOND + PageStore.SAVE_DELAY_NS - 1), "not saved before the delay");
        Check.eq(0L, store.stats.workerPagesSaved, "no save yet");
        Check.that(store.idle(SECOND + PageStore.SAVE_DELAY_NS), "saved after the delay");
        Check.eq(1L, store.stats.workerPagesSaved, "one save");
        Check.that(!store.idle(SECOND + PageStore.SAVE_DELAY_NS + 1), "nothing left to save");

        // Kept while it is being drawn.
        long later = SECOND + PageStore.KEEP_NS + 5 * SECOND;
        page.keepAliveAt = later;
        store.idle(later + 3 * SECOND);
        Check.that(store.pageIfLoaded(0, 0) == page, "a page in use stays");

        // Dropped once nobody has needed it for a while.
        long muchLater = later + PageStore.KEEP_NS + 10 * SECOND;
        store.idle(muchLater);
        Check.eq(null, store.pageIfLoaded(0, 0), "an idle page is dropped");
        RegionPage removed = store.pollEvent();
        Check.that(removed == page && page.removed, "and its removal announced");

        // It comes back from disk with its contents, as a new page object.
        store.loadForDisplay(Regions.key(0, 0), muchLater);
        RegionPage again = store.pageIfLoaded(0, 0);
        Check.that(again != null && again != page && again.state[0] == GRASS, "reloaded on demand");
        Check.that(store.pollEvent() == again && !again.removed, "and announced");

        // A page with unsaved changes is never dropped by the idle scan.
        store.applyChunk(1, 0, flat(SAND, 64, 0), muchLater);
        again.keepAliveAt = 0;
        store.idle(muchLater + PageStore.SAVE_DELAY_NS - 1);
        Check.that(store.pageIfLoaded(0, 0) == again, "unsaved page kept");
    }

    static void pageCap() throws IOException {
        Path dir = tempDir();
        PageStore store = store(dir, 4);
        for (int r = 0; r < 10; r++) {
            store.applyChunk(r * 32, 0, flat(GRASS, 64, 0), (r + 1) * SECOND);
            Check.that(store.pageCount() <= 4, "at most four pages in memory");
            Check.that(store.pageIfLoaded(r, 0) != null, "the newest page survives");
        }
        Check.eq(null, store.pageIfLoaded(0, 0), "oldest page gone");
        // Evicted pages were saved first, so nothing recorded is lost.
        store.flush();
        Check.eq(10, new RegionFiles(dir).list().length, "all ten regions on disk");

        int added = 0;
        int removed = 0;
        for (RegionPage p; (p = store.pollEvent()) != null; ) {
            if (p.removed) {
                removed++;
            } else {
                added++;
            }
        }
        // Ten pages were announced and six of those dropped again. Both events of a dropped page
        // carry the same object, so by the time they are read both show it as removed.
        Check.eq(16, added + removed, "sixteen events");
        Check.eq(4, added, "four pages still live");
    }

    static void memoryOnly() throws IOException {
        PageStore store = store(null, 8);
        store.applyChunk(0, 0, flat(GRASS, 64, 0), SECOND);
        store.idle(SECOND + PageStore.SAVE_DELAY_NS + PageStore.KEEP_NS + 100 * SECOND);
        store.idle(SECOND + 2 * (PageStore.SAVE_DELAY_NS + PageStore.KEEP_NS + 100 * SECOND));
        Check.that(store.pageIfLoaded(0, 0) != null, "without files, pages are kept rather than lost");
        Check.eq(0L, store.stats.workerPagesSaved, "nothing saved");
        Check.eq(0, store.diskIndex().length, "nothing indexed");
        store.close();
        Check.eq(0, store.pageCount(), "close releases the pages");
    }

    static void worker() {
        MapWorker worker = new MapWorker("lightmap-test-worker");
        try {
            List<Integer> order = java.util.Collections.synchronizedList(new ArrayList<>());
            AtomicInteger upkeep = new AtomicInteger();
            worker.setIdle(() -> upkeep.incrementAndGet() < 0);
            for (int i = 0; i < 200; i++) {
                int n = i;
                worker.submit(() -> order.add(n));
            }
            System.out.println("  (one logged \"map task failed\" error is expected here)");
            worker.submit(() -> {
                throw new IllegalStateException("a failing task must not stop the worker");
            });
            worker.submit(() -> order.add(200));
            Check.that(worker.await(5000), "worker drains");
            Check.eq(201, order.size(), "all tasks ran");
            boolean sorted = true;
            for (int i = 0; i < order.size(); i++) {
                sorted &= order.get(i) == i;
            }
            Check.that(sorted, "in submission order");
            Check.that(!worker.isWorkerThread(), "caller is not the worker");
            long deadline = System.nanoTime() + 2 * SECOND;
            while (upkeep.get() == 0 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            Check.that(upkeep.get() > 0, "upkeep ran");
        } finally {
            worker.shutdown();
        }
    }

    private static void markers() throws Exception {
        Markers markers = new Markers();
        Markers.Marker home = new Markers.Marker(-120, 340, "Home", true);
        Markers.Marker odd = new Markers.Marker(7, -8, "tab\there \\ back\nslash é", false);
        markers.add(home);
        markers.add(odd);
        Markers.Marker[] before = markers.all();
        Check.eq(List.of(home, odd), List.of(before), "added in order");

        Markers.Marker renamed = new Markers.Marker(-120, 340, "Base", false);
        markers.replace(home, renamed);
        Check.eq(List.of(renamed, odd), List.of(markers.all()), "changed in place");
        Check.eq(List.of(home, odd), List.of(before), "an earlier snapshot is never changed");
        markers.replace(home, null);
        Check.eq(2, markers.all().length, "replacing a marker that is gone does nothing");
        markers.replace(renamed, null);
        Check.eq(List.of(odd), List.of(markers.all()), "removed");

        // Markers read from disk go in front of one placed while the read was still running.
        markers.addLoaded(List.of(home));
        Check.eq(List.of(home, odd), List.of(markers.all()), "loaded markers first");

        // The file keeps every field, including names with tabs, newlines and backslashes.
        Path dir = Files.createTempDirectory("lightmap-markers-test");
        RegionFiles files = new RegionFiles(dir.resolve("world"));
        Check.eq(null, files.readMarkers(), "no file yet");
        files.writeMarkers(Markers.encode(markers.all()));
        Check.eq(List.of(home, odd), Markers.decode(files.readMarkers()), "file round-trip");
        Check.eq(0, files.list().length, "the marker file is not taken for a region");
        Check.eq(List.of(), Markers.decode(Markers.encode(new Markers.Marker[0])), "empty list round-trip");

        // A damaged line costs only that marker; a foreign file is refused.
        String damaged = "lightmap-markers 1\n1\t2\t1\tGood\nx\t2\t1\tBad number\n3\t4\tyes\tBad flag\n5\t6\n\n7\t8\t0\tAlso\tgood\n";
        Check.eq(List.of(new Markers.Marker(1, 2, "Good", true), new Markers.Marker(7, 8, "Also\tgood", false)),
                Markers.decode(damaged.getBytes(java.nio.charset.StandardCharsets.UTF_8)), "damaged lines are skipped");
        for (String foreign : new String[] {"", "lightmap-markers 2\n1\t2\t1\tA\n", "hello\n"}) {
            boolean refused = false;
            try {
                Markers.decode(foreign.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (IOException e) {
                refused = true;
            }
            Check.that(refused, "refuses " + foreign.replace("\n", "|"));
        }
    }

    private static void settings() throws Exception {
        Path dir = Files.createTempDirectory("lightmap-settings-test");
        Path file = dir.resolve("config").resolve("lightmap.properties");
        try {
            Settings.load(file);
            Check.that(Settings.minimap() && Settings.rotate(), "defaults: minimap on, rotating");
            Check.eq(100, Settings.opacity(), "default opacity");
            Check.eq(368, Settings.size(), "default size");
            Check.that(!Files.exists(file), "loading does not create the file");

            Settings.setOpacity(140);
            Check.eq(100, Settings.opacity(), "opacity is capped at 100");
            Settings.setOpacity(-3);
            Check.eq(0, Settings.opacity(), "and at 0");
            Settings.setSize(5);
            Check.eq(Settings.SIZE_MIN, Settings.size(), "size has a lower limit");

            Settings.setOpacity(35);
            Settings.setSize(256);
            Settings.setRotate(false);
            Settings.setMinimap(false);
            Settings.save();
            Check.that(Files.isRegularFile(file), "save creates the file and its folder");
            Settings.setOpacity(100);
            Settings.setSize(368);
            Settings.setRotate(true);
            Settings.setMinimap(true);
            Settings.load(file);
            Check.eq(35, Settings.opacity(), "opacity survives a reload");
            Check.eq(256, Settings.size(), "size survives a reload");
            Check.that(!Settings.rotate() && !Settings.minimap(), "switches survive a reload");

            Files.writeString(file, "lightmap.minimap.opacity=lots\nlightmap.minimap.size=99999\nlightmap.minimap=maybe\n");
            Settings.load(file);
            Check.eq(100, Settings.opacity(), "unreadable number falls back to the default");
            Check.eq(Settings.SIZE_MAX, Settings.size(), "out-of-range number is clamped");
            Check.that(Settings.minimap(), "unreadable switch falls back to the default");

            // A value pinned by a system property is used, shown as pinned, and never written to the file.
            Files.writeString(file, "lightmap.minimap.opacity=35\n");
            System.setProperty(Settings.OPACITY, "60");
            try {
                Settings.load(file);
                Check.eq(60, Settings.opacity(), "system property wins over the file");
                Check.that(Settings.pinned(Settings.OPACITY) && !Settings.pinned(Settings.SIZE), "and is reported as pinned");
                Settings.setSize(512);
                Settings.save();
            } finally {
                System.clearProperty(Settings.OPACITY);
            }
            Settings.load(file);
            Check.eq(35, Settings.opacity(), "the file keeps the player's own value");
            Check.eq(512, Settings.size(), "while other changes are saved");
        } finally {
            Settings.load(dir.resolve("none.properties"));
        }
    }
}
