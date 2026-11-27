package dev.lightmap.mc;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.lightmap.core.Check;
import dev.lightmap.core.ChunkColumns;
import dev.lightmap.core.MapView;
import dev.lightmap.core.PageStore;
import dev.lightmap.core.Palette;
import dev.lightmap.core.RegionFiles;
import dev.lightmap.core.RegionPage;
import dev.lightmap.core.Regions;
import dev.lightmap.core.Shading;
import dev.lightmap.core.Stats;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Self-tests that need the game's classes but not a running game: they start the registries the
 * way a dedicated server does and then drive the real capture, colouring and storage code with
 * real block states, chunk sections and heightmaps. Run with {@code gradlew gameSelfTest}.
 *
 * <p>Passing a file name as the first argument also writes the recorded test world as a PNG.
 */
public final class GameTests {
    private static final int MIN_Y = -64;
    private static final int HEIGHT = 384;
    private static final int SEA = 62;
    private static final long SECOND = 1_000_000_000L;

    private static StateTables tables;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        long started = System.nanoTime();
        tables = StateTables.build();
        System.out.printf("  state table built in %.0f ms%n", (System.nanoTime() - started) / 1e6);

        Check.run("state table: map colours, fluids and invisible blocks", GameTests::stateTable);
        Check.run("state names: every state survives a round trip through its name", GameTests::stateNames);
        Check.run("capture: copies only the sections around the surface", GameTests::captureExtent);
        Check.run("resolve: surface, see-through blocks, fluid depth and empty columns", GameTests::resolveColumns);
        Check.run("quads: page corners, texture mapping and facing", GameTests::quads);
        Check.run("layering: the map's quads draw in order, under later HUD elements", GameTests::layering);
        Check.run("end to end: record a world, change a block, save and reload", () -> endToEnd(args.length > 0 ? args[0] : null));
        Check.run("uploads: legal calls, within budget, and the textures end up exact", () -> UploadTests.run(tables));
        Check.run("session, minimap and world map: recorded, drawn in the right place, saved, reloaded", ScreenTests::run);
        Check.finish();
    }

    // ------------------------------------------------------------------ building real chunks

    /** A real {@link LevelChunk} with real sections and heightmap, minus the level around it. */
    static final class TestChunk {
        final LevelChunk chunk;
        final LevelChunkSection[] sections = new LevelChunkSection[HEIGHT >> 4];
        final List<PalettedContainer<BlockState>> states = new ArrayList<>();
        final Heightmap surface;

        TestChunk(int cx, int cz) throws Exception {
            Strategy<BlockState> strategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
            for (int i = 0; i < sections.length; i++) {
                states.add(new PalettedContainer<>(Blocks.AIR.defaultBlockState(), strategy));
            }
            chunk = (LevelChunk) unsafe().allocateInstance(LevelChunk.class);
            Map<Heightmap.Types, Heightmap> heightmaps = new EnumMap<>(Heightmap.Types.class);
            set(chunk, "chunkPos", new ChunkPos(cx, cz));
            set(chunk, "levelHeightAccessor", LevelHeightAccessor.create(MIN_Y, HEIGHT));
            set(chunk, "heightmaps", heightmaps);
            set(chunk, "sections", sections);
            // The chunk asks its level one thing when blocks are read through it: "is this the
            // debug world?" An empty client level object answers that (no).
            GameTests.set(LevelChunk.class, chunk, "level", unsafe().allocateInstance(ClientLevel.class));
            surface = new Heightmap(chunk, Heightmap.Types.WORLD_SURFACE);
            heightmaps.put(Heightmap.Types.WORLD_SURFACE, surface);
        }

        /** Blocks must be placed bottom-up within a column, as the heightmap is updated in passing. */
        void put(int x, int y, int z, BlockState state) {
            states.get((y - MIN_Y) >> 4).set(x, y & 15, z, state);
            surface.update(x, y, z, state);
        }

        /** Wraps the filled containers into sections; call once all blocks are placed. */
        LevelChunk finish() {
            for (int i = 0; i < sections.length; i++) {
                sections[i] = new LevelChunkSection(states.get(i), null);
            }
            return chunk;
        }

        private static void set(Object target, String field, Object value) throws Exception {
            GameTests.set(ChunkAccess.class, target, field, value);
        }
    }

    /** Sets a private field; the tests use it to assemble game objects without a running game. */
    static void set(Class<?> owner, Object target, String field, Object value) throws Exception {
        Field f = owner.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** Reads a private field. */
    static Object get(Class<?> owner, Object target, String field) throws Exception {
        Field f = owner.getDeclaredField(field);
        f.setAccessible(true);
        return f.get(target);
    }

    /** For creating a game object without running its constructor. */
    @SuppressWarnings("removal")
    static sun.misc.Unsafe unsafe() throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (sun.misc.Unsafe) f.get(null);
    }

    static int id(BlockState state) {
        return Block.getId(state);
    }

    static ChunkColumns resolve(LevelChunk chunk) {
        ChunkColumns columns = new ChunkColumns();
        ChunkSnapshot.capture(chunk, new Stats()).resolve(tables.palette, tables.air, columns);
        return columns;
    }

    // ------------------------------------------------------------------ tests

    static void stateTable() {
        Palette p = tables.palette;
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();
        BlockState lava = Blocks.LAVA.defaultBlockState();
        BlockState topSlab = Blocks.OAK_SLAB.defaultBlockState()
                .setValue(SlabBlock.TYPE, SlabType.TOP).setValue(BlockStateProperties.WATERLOGGED, true);
        BlockState bottomSlab = Blocks.OAK_SLAB.defaultBlockState()
                .setValue(SlabBlock.TYPE, SlabType.BOTTOM).setValue(BlockStateProperties.WATERLOGGED, true);

        Check.eq(0, tables.air, "air is state 0");
        Check.that(!p.has(id(Blocks.AIR.defaultBlockState()), Palette.VISIBLE), "air is not drawn");
        Check.that(!p.has(id(Blocks.GLASS.defaultBlockState()), Palette.VISIBLE), "glass is seen through");
        Check.that(!p.has(id(Blocks.TORCH.defaultBlockState()), Palette.VISIBLE), "a torch is seen through");

        Check.that(p.has(id(stone), Palette.VISIBLE) && !p.has(id(stone), Palette.FLUID), "stone is solid");
        Check.eq(MapColor.STONE.col, p.rgb[id(stone)], "stone colour");
        Check.eq(MapColor.GRASS.col, p.rgb[id(Blocks.GRASS_BLOCK.defaultBlockState())], "grass colour");
        Check.eq(MapColor.SAND.col, p.rgb[id(Blocks.SAND.defaultBlockState())], "sand colour");
        Check.eq(MapColor.SNOW.col, p.rgb[id(Blocks.SNOW_BLOCK.defaultBlockState())], "snow colour");

        Check.that(p.has(id(water), Palette.VISIBLE) && p.has(id(water), Palette.FLUID) && p.has(id(water), Palette.HAS_FLUID), "water is a fluid");
        Check.eq(MapColor.WATER.col, p.rgb[id(water)], "water colour");
        Check.that(p.has(id(lava), Palette.FLUID), "lava is a fluid");
        Check.eq(MapColor.FIRE.col, p.rgb[id(lava)], "lava colour");

        int seagrass = id(Blocks.SEAGRASS.defaultBlockState());
        Check.that(p.has(seagrass, Palette.FLUID), "seagrass is drawn as the water it stands in");
        Check.eq(MapColor.WATER.col, p.rgb[seagrass], "seagrass colour");
        Check.that(p.has(id(bottomSlab), Palette.FLUID), "a waterlogged bottom slab is drawn as water");
        Check.that(!p.has(id(topSlab), Palette.FLUID) && p.has(id(topSlab), Palette.HAS_FLUID), "a waterlogged top slab is drawn as wood but holds water");
        Check.eq(MapColor.WOOD.col, p.rgb[id(topSlab)], "top slab colour");

        int visible = 0;
        int fluid = 0;
        for (int i = 0; i < p.size(); i++) {
            visible += p.has(i, Palette.VISIBLE) ? 1 : 0;
            fluid += p.has(i, Palette.FLUID) ? 1 : 0;
        }
        System.out.println("  states: " + p.size() + ", drawn: " + visible + ", drawn as fluid: " + fluid);
        Check.that(p.size() > 20_000 && visible > p.size() * 3 / 4, "most block states have a colour");
    }

    static void stateNames() {
        int mismatches = 0;
        int checked = 0;
        for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
            int id = Block.getId(state);
            String name = tables.nameOf(id);
            if (tables.idOf(name) != id) {
                if (mismatches++ < 5) {
                    System.out.println("  ! " + name + " came back as " + tables.nameOf(tables.idOf(name)));
                }
            }
            checked++;
        }
        Check.eq(0, mismatches, "states that do not round-trip, of " + checked);
        Check.eq("minecraft:stone", tables.nameOf(id(Blocks.STONE.defaultBlockState())), "plain name");
        Check.that(tables.nameOf(id(Blocks.OAK_LOG.defaultBlockState())).startsWith("minecraft:oak_log[axis="), "name with properties");
        Check.eq(tables.air, tables.idOf("somemod:removed_block"), "unknown block");
        Check.eq(tables.air, tables.idOf("minecraft:stone[nonsense=true]"), "unknown property");
        Check.eq(tables.air, tables.idOf("not a block state at all"), "garbage");
    }

    static void captureExtent() throws Exception {
        // Flat ground at y=70 with one column dug down to y=20: only the sections from the pit's
        // floor (minus the margin) up to the surface are copied, not the whole chunk.
        TestChunk t = new TestChunk(3, -7);
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int top = x == 5 && z == 9 ? 20 : 70;
                for (int y = MIN_Y; y <= top; y++) {
                    t.put(x, y, z, stone);
                }
            }
        }
        Stats stats = new Stats();
        ChunkSnapshot snapshot = ChunkSnapshot.capture(t.finish(), stats);
        Check.eq(3, snapshot.cx, "chunk x");
        Check.eq(-7, snapshot.cz, "chunk z");
        // Lowest top is 20; 20 - 32 = -12, which lies in the section starting at y=-16.
        Check.eq(-16, snapshot.floor, "floor of the copy");
        // Sections y=-16..79 are six sections.
        Check.eq(6L, stats.clientSectionsCopied, "sections copied");
        Check.eq(-16, snapshot.watchFrom, "block changes below the copy cannot matter");

        ChunkColumns columns = new ChunkColumns();
        snapshot.resolve(tables.palette, tables.air, columns);
        Check.eq((short) 70, columns.height[0], "surface height");
        Check.eq((short) 20, columns.height[9 << 4 | 5], "pit height at x=5, z=9");
        Check.eq((short) 70, columns.height[5 << 4 | 9], "x and z are not swapped");

        // An empty chunk costs nothing to copy and resolves to columns with nothing to draw.
        Stats emptyStats = new Stats();
        ChunkSnapshot empty = ChunkSnapshot.capture(new TestChunk(0, 0).finish(), emptyStats);
        Check.eq(0L, emptyStats.clientSectionsCopied, "empty chunk copies no sections");
        Check.eq(MIN_Y, empty.floor, "empty chunk floor is the bottom of the world");
        Check.eq(MIN_Y, empty.watchFrom, "any block placed in an empty chunk matters");
        empty.resolve(tables.palette, tables.air, columns);
        Check.eq(tables.air, columns.state[77], "empty column state");
        Check.that(columns.height[77] != RegionPage.NO_DATA, "an empty column is still recorded");
        Check.eq(Shading.VOID, Shading.pixel(tables.palette, columns.state[77], columns.height[77], 0, RegionPage.NO_DATA), "and drawn as void");

        // A chunk that arrives with blocks but an empty heightmap gets its heightmap rebuilt.
        TestChunk bare = new TestChunk(0, 0);
        for (int y = 60; y <= 66; y++) {
            bare.states.get((y - MIN_Y) >> 4).set(4, y & 15, 4, stone);
        }
        ChunkColumns rebuilt = new ChunkColumns();
        ChunkSnapshot.capture(bare.finish(), new Stats()).resolve(tables.palette, tables.air, rebuilt);
        Check.eq((short) 66, rebuilt.height[4 << 4 | 4], "height found after the heightmap was rebuilt");
        Check.eq(id(stone), rebuilt.state[4 << 4 | 4], "state found after the heightmap was rebuilt");
        Check.eq(tables.air, rebuilt.state[0], "other columns stay empty");

        // Blocks at the very top and bottom of the world are within range.
        TestChunk extremes = new TestChunk(0, 0);
        extremes.put(1, MIN_Y, 1, stone);
        extremes.put(2, MIN_Y + HEIGHT - 1, 2, stone);
        ChunkColumns ex = resolve(extremes.finish());
        Check.eq((short) (MIN_Y + HEIGHT - 1), ex.height[2 << 4 | 2], "block at the build limit");
        Check.eq(id(stone), ex.state[2 << 4 | 2], "state at the build limit");
    }

    static void resolveColumns() throws Exception {
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();
        BlockState glass = Blocks.GLASS.defaultBlockState();
        BlockState sand = Blocks.SAND.defaultBlockState();

        TestChunk t = new TestChunk(0, 0);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                for (int y = 40; y < 64; y++) {
                    t.put(x, y, z, stone);
                }
                t.put(x, 64, z, grass);
            }
        }
        // (1,0): a glass tower on the grass. (2,0): short grass on the grass.
        for (int y = 65; y < 75; y++) {
            t.put(1, y, 0, glass);
        }
        t.put(2, 65, 0, Blocks.SHORT_GRASS.defaultBlockState());
        // (3,0): a torch on the grass, which has no map colour.
        t.put(3, 65, 0, Blocks.TORCH.defaultBlockState());
        LevelChunk land = t.finish();
        ChunkColumns c = resolve(land);
        Check.eq(id(grass), c.state[0], "plain column state");
        Check.eq((short) 64, c.height[0], "plain column height");
        Check.eq((byte) 0, c.depth[0], "land has no depth");
        Check.eq(id(grass), c.state[1], "glass is looked through");
        Check.eq((short) 64, c.height[1], "to the ground below");
        Check.eq(id(Blocks.SHORT_GRASS.defaultBlockState()), c.state[2], "a plant is the top block");
        Check.eq((short) 65, c.height[2], "at its own height");
        Check.eq(id(grass), c.state[3], "a torch is looked through");

        // A pond: sand floor at 56, water from 57 to 62, seagrass on the floor in one column,
        // and ice on top of another.
        TestChunk p = new TestChunk(0, 0);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                for (int y = 40; y <= 56; y++) {
                    p.put(x, y, z, y == 56 ? sand : stone);
                }
                boolean weed = x == 4 && z == 4;
                for (int y = 57; y <= SEA; y++) {
                    p.put(x, y, z, weed && y == 57 ? Blocks.SEAGRASS.defaultBlockState() : water);
                }
            }
        }
        p.put(6, SEA + 1, 6, Blocks.ICE.defaultBlockState());
        ChunkColumns pond = resolve(p.finish());
        Check.eq(id(water), pond.state[0], "water surface state");
        Check.eq((short) SEA, pond.height[0], "water surface height");
        Check.eq((byte) 6, pond.depth[0], "six blocks of water");
        Check.eq((byte) 6, pond.depth[4 << 4 | 4], "seagrass counts as water");
        Check.eq(id(Blocks.ICE.defaultBlockState()), pond.state[6 << 4 | 6], "ice covers the water");
        Check.eq((byte) 0, pond.depth[6 << 4 | 6], "and is land");

        // Water deeper than the copy reaches is measured as far as the copy goes, not further.
        TestChunk deep = new TestChunk(0, 0);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                deep.put(x, MIN_Y, z, stone);
                for (int y = MIN_Y + 1; y <= SEA; y++) {
                    deep.put(x, y, z, water);
                }
            }
        }
        ChunkSnapshot snapshot = ChunkSnapshot.capture(deep.finish(), new Stats());
        ChunkColumns ocean = new ChunkColumns();
        snapshot.resolve(tables.palette, tables.air, ocean);
        Check.eq(SEA - snapshot.floor + 1, ocean.depth[0] & 0xFF, "depth capped by the copy");
        Check.that((ocean.depth[0] & 0xFF) >= 32, "which reaches at least the margin");

        // A column of nothing but glass has nothing to draw.
        TestChunk dome = new TestChunk(0, 0);
        for (int y = 60; y < 70; y++) {
            dome.put(8, y, 8, glass);
        }
        ChunkSnapshot domeSnapshot = ChunkSnapshot.capture(dome.finish(), new Stats());
        ChunkColumns d = new ChunkColumns();
        domeSnapshot.resolve(tables.palette, tables.air, d);
        Check.eq(tables.air, d.state[8 << 4 | 8], "glass-only column");
        Check.that(d.height[8 << 4 | 8] != RegionPage.NO_DATA, "is still recorded");
        // The rest of that chunk is open void: the copy stays near the glass, but a block placed
        // anywhere below, in any of the empty columns, would be visible and must trigger a re-copy.
        Check.eq(32, domeSnapshot.floor, "copy stays near the only blocks");
        Check.eq(MIN_Y, domeSnapshot.watchFrom, "but the whole height is watched");
    }

    /** Records what a quad sends to the GUI renderer. */
    static final class Vertices implements VertexConsumer {
        final List<float[]> list = new ArrayList<>();
        /** The colour of the last vertex, as ARGB. */
        int color;

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            list.add(new float[]{x, y, Float.NaN, Float.NaN});
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            list.getLast()[2] = u;
            list.getLast()[3] = v;
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            this.color = color;
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv3(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }

        /** Twice the signed area of the first three vertices; its sign is the facing. */
        float turn() {
            float[] a = list.get(0);
            float[] b = list.get(1);
            float[] c = list.get(2);
            return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
        }
    }

    static void quads() {
        ScreenRectangle area = new ScreenRectangle(0, 0, 100, 100);
        // The stock blit emits top-left, bottom-left, bottom-right, top-right. The GUI pipelines
        // cull back faces, so every quad of ours has to turn the same way as that one.
        float reference = Math.signum((0f - 0f) * (1f - 0f) - (1f - 0f) * (1f - 0f));

        MapQuad fill = new MapQuad();
        fill.fill(10, 20, 30, 40, 0xFF000000, area);
        Vertices f = new Vertices();
        fill.buildVertices(f);
        Check.eq(4, f.list.size(), "a fill is one quad");
        Check.eq(reference, Math.signum(f.turn()), "fill faces the viewer");
        Check.that(Float.isNaN(f.list.get(0)[2]), "a fill has no texture coordinates");

        // North up: the page's north-west corner is top-left and carries texel (0,0).
        MapView view = new MapView();
        view.set(0, 0, 50, 50, 0.1f, 0f);
        MapQuad page = new MapQuad();
        page.page(view, 0, 0, TextureSetup.noTexture(), -1, area, area);
        Vertices v = new Vertices();
        page.buildVertices(v);
        Check.eq(4, v.list.size(), "a page is one quad");
        vertex(v.list.get(0), 50f, 50f, 0f, 0f, "north-west corner at the view centre, texel 0,0");
        vertex(v.list.get(1), 50f, 101.2f, 0f, 1f, "south-west corner below it, texel 0,1");
        vertex(v.list.get(2), 101.2f, 101.2f, 1f, 1f, "south-east corner, texel 1,1");
        vertex(v.list.get(3), 101.2f, 50f, 1f, 0f, "north-east corner, texel 1,0");
        Check.eq(reference, Math.signum(v.turn()), "page faces the viewer");

        // Rotation must never flip a page or the marker to face away.
        MapQuad arrow = new MapQuad();
        for (int degrees = -180; degrees <= 180; degrees += 15) {
            view.set(1234.5, -987.25, 50, 50, 0.5f, MapView.headingUp(degrees));
            page.page(view, 2, -2, TextureSetup.noTexture(), -1, area, area);
            Vertices rotated = new Vertices();
            page.buildVertices(rotated);
            Check.eq(reference, Math.signum(rotated.turn()), "page facing at yaw " + degrees);

            double heading = Math.toRadians(degrees);
            arrow.arrow(50, 50, (float) -Math.sin(heading), (float) Math.cos(heading), 4f, -1, area);
            Vertices a = new Vertices();
            arrow.buildVertices(a);
            Check.eq(reference, Math.signum(a.turn()), "marker facing at yaw " + degrees);
        }

        // The marker's tip is in the direction it is given: up the screen for (0,-1).
        arrow.arrow(50, 50, 0, -1, 4f, -1, area);
        Vertices up = new Vertices();
        arrow.buildVertices(up);
        Check.that(up.list.get(0)[1] < 50 && up.list.get(1)[1] > 50 && up.list.get(2)[1] > 50, "marker tip points up");
        Check.that(up.list.get(1)[0] < 50 && up.list.get(2)[0] > 50, "with its base either side");
    }

    /**
     * The minimap submits its backdrop through the normal path and then adds its pages and marker
     * straight to the backdrop's layer. This checks, against the game's real render-state class,
     * that they come out in that order and that an element submitted later over the same area
     * (the chat, say) still lands above all of them.
     */
    static void layering() {
        GuiRenderState state = new GuiRenderState();
        ScreenRectangle screen = new ScreenRectangle(0, 0, 400, 300);
        ScreenRectangle map = new ScreenRectangle(5, 5, 184, 184);

        MapQuad vignette = new MapQuad();
        vignette.fill(0, 0, 400, 300, 0x40000000, screen);
        MapQuad hotbar = new MapQuad();
        hotbar.fill(110, 278, 290, 300, -1, new ScreenRectangle(110, 278, 180, 22));
        MapQuad backdrop = new MapQuad();
        backdrop.fill(5, 5, 189, 189, -1, map);
        MapQuad pageA = new MapQuad();
        MapQuad pageB = new MapQuad();
        MapQuad marker = new MapQuad();
        MapQuad chat = new MapQuad();
        chat.fill(0, 150, 320, 280, -1, new ScreenRectangle(0, 150, 320, 130));
        MapQuad elsewhere = new MapQuad();
        elsewhere.fill(300, 0, 400, 20, -1, new ScreenRectangle(300, 0, 100, 20));

        state.addGuiElement(vignette);
        state.addGuiElement(hotbar);
        state.addGuiElement(backdrop);
        state.addGlyphToCurrentLayer(pageA);
        state.addGlyphToCurrentLayer(pageB);
        state.addGlyphToCurrentLayer(marker);
        state.addGuiElement(elsewhere);
        state.addGuiElement(chat);

        List<Object> order = new ArrayList<>();
        state.forEachElement(order::add, GuiRenderState.TraverseRange.ALL);
        Check.eq(8, order.size(), "every element is drawn");
        Check.that(order.indexOf(vignette) < order.indexOf(backdrop), "full-screen overlay under the map");
        Check.that(order.indexOf(backdrop) < order.indexOf(pageA), "backdrop under the pages");
        Check.that(order.indexOf(pageA) < order.indexOf(pageB) && order.indexOf(pageB) < order.indexOf(marker), "pages then marker, in submission order");
        Check.that(order.indexOf(marker) < order.indexOf(chat), "a later element over the map area draws above the marker");
    }

    /** Pass NaN for u and v where the vertex should have no texture coordinates. */
    static void vertex(float[] actual, float x, float y, float u, float v, String what) {
        Check.near(x, actual[0], 2e-3, what + " (x)");
        Check.near(y, actual[1], 2e-3, what + " (y)");
        Check.that(Float.compare(u, actual[2]) == 0, what + " (u): expected " + u + " but was " + actual[2]);
        Check.that(Float.compare(v, actual[3]) == 0, what + " (v): expected " + v + " but was " + actual[3]);
    }

    // ------------------------------------------------------------------ end to end

    /** Smooth pseudo-random terrain height, so the picture has hills, shores and a sea. */
    static int terrain(int x, int z) {
        double h = 66
                + 26 * noise(x / 190.0, z / 190.0)
                + 12 * noise(x / 61.0 + 40, z / 61.0 - 17)
                + 4 * noise(x / 17.0 - 5, z / 17.0 + 90);
        return (int) Math.round(h);
    }

    static double noise(double x, double z) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        double fx = smooth(x - x0);
        double fz = smooth(z - z0);
        double a = lattice(x0, z0) + (lattice(x0 + 1, z0) - lattice(x0, z0)) * fx;
        double b = lattice(x0, z0 + 1) + (lattice(x0 + 1, z0 + 1) - lattice(x0, z0 + 1)) * fx;
        return a + (b - a) * fz;
    }

    static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    static double lattice(int x, int z) {
        long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return (h & 0xFFFFFF) / (double) 0xFFFFFF * 2 - 1;
    }

    static LevelChunk terrainChunk(int cx, int cz) throws Exception {
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();
        TestChunk t = new TestChunk(cx, cz);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int wx = (cx << 4) + x;
                int wz = (cz << 4) + z;
                int h = terrain(wx, wz);
                BlockState top;
                if (h > 98) {
                    top = Blocks.SNOW_BLOCK.defaultBlockState();
                } else if (h > 86) {
                    top = stone;
                } else if (h <= SEA + 1) {
                    top = Blocks.SAND.defaultBlockState();
                } else {
                    top = Blocks.GRASS_BLOCK.defaultBlockState();
                }
                for (int y = Math.max(MIN_Y, h - 6); y < h; y++) {
                    t.put(x, y, z, stone);
                }
                t.put(x, h, z, top);
                for (int y = h + 1; y <= SEA; y++) {
                    t.put(x, y, z, water);
                }
                // Woodland where a second noise field is high: leaves a few blocks above the grass.
                if (top.is(Blocks.GRASS_BLOCK) && noise(wx / 23.0 + 300, wz / 23.0 + 300) > 0.25 && (lattice(wx, wz) > 0.1)) {
                    t.put(x, h + 4, z, Blocks.OAK_LEAVES.defaultBlockState());
                }
            }
        }
        return t.finish();
    }

    static void endToEnd(String imageFile) throws Exception {
        Path dir = Files.createTempDirectory("lightmap-game-test");
        Stats stats = new Stats();
        PageStore store = new PageStore(new RegionFiles(dir), 64, stats);
        store.open(tables.palette, tables);
        ChunkColumns columns = new ChunkColumns();

        // Record 48x48 chunks around the origin: 768 blocks square, across four regions.
        long captureNanos = 0;
        long applyNanos = 0;
        int chunks = 0;
        for (int cz = -24; cz < 24; cz++) {
            for (int cx = -24; cx < 24; cx++) {
                LevelChunk chunk = terrainChunk(cx, cz);
                long t0 = System.nanoTime();
                ChunkSnapshot snapshot = ChunkSnapshot.capture(chunk, stats);
                long t1 = System.nanoTime();
                snapshot.resolve(tables.palette, tables.air, columns);
                store.applyChunk(cx, cz, columns, SECOND);
                long t2 = System.nanoTime();
                captureNanos += t1 - t0;
                applyNanos += t2 - t1;
                chunks++;
            }
        }
        System.out.printf("  %d chunks: capture %.1f us each (client thread), resolve + colour %.1f us each (worker), %.1f sections copied per chunk%n",
                chunks, captureNanos / 1e3 / chunks, applyNanos / 1e3 / chunks, stats.clientSectionsCopied / (double) chunks);
        Check.eq(4, store.pageCount(), "four regions");
        // Chunks are recorded north to south, so a chunk's southern neighbour is never there yet
        // to be re-shaded: exactly one tile is marked per chunk.
        Check.eq((long) chunks, stats.workerTilesMarked, "one tile marked per chunk");

        // The recorded picture is not degenerate: it has water, land and relief.
        RegionPage page = store.pageIfLoaded(0, 0);
        int unexplored = 0;
        int water = 0;
        int land = 0;
        java.util.Set<Integer> shades = new java.util.HashSet<>();
        for (int rz = -1; rz <= 0; rz++) {
            for (int rx = -1; rx <= 0; rx++) {
                RegionPage p = store.pageIfLoaded(rx, rz);
                for (int i = 0; i < Regions.AREA; i++) {
                    shades.add(p.pixels[i]);
                    if (p.height[i] == RegionPage.NO_DATA) {
                        unexplored++;
                    } else if (tables.palette.has(p.state[i], Palette.FLUID)) {
                        water++;
                    } else {
                        land++;
                    }
                }
            }
        }
        System.out.println("  recorded: water=" + water + " land=" + land + " unexplored=" + unexplored + " distinct shades=" + shades.size());
        Check.that(water > 10_000 && land > 10_000, "both water and land recorded");
        Check.eq(4 * Regions.AREA - 768 * 768, unexplored, "everything outside the recorded square is unexplored");
        Check.that(shades.size() > 30, "many distinct shades");

        // Recording the same chunks again changes nothing and queues nothing.
        drainUploads(store);
        long tilesBefore = stats.workerTilesMarked;
        for (int cz = -2; cz < 2; cz++) {
            for (int cx = -2; cx < 2; cx++) {
                ChunkSnapshot.capture(terrainChunk(cx, cz), stats).resolve(tables.palette, tables.air, columns);
                store.applyChunk(cx, cz, columns, 2 * SECOND);
            }
        }
        Check.eq(tilesBefore, stats.workerTilesMarked, "unchanged chunks mark no tiles");
        Check.eq(null, store.pollUpload(), "and queue no uploads");

        // Placing one block on top of one column re-uploads exactly that chunk's tile.
        TestChunk edited = new TestChunk(1, 1);
        LevelChunk original = terrainChunk(1, 1);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                for (int y = MIN_Y; y < MIN_Y + HEIGHT; y++) {
                    BlockState s = original.getSections()[(y - MIN_Y) >> 4].getBlockState(x, y & 15, z);
                    if (!s.isAir()) {
                        edited.put(x, y, z, s);
                    }
                }
            }
        }
        int topOfColumn = edited.surface.getFirstAvailable(7, 7);
        // Mid-chunk, so the change cannot affect the shading of the chunk to the south.
        edited.put(7, topOfColumn, 7, Blocks.GOLD_BLOCK.defaultBlockState());
        ChunkSnapshot.capture(edited.finish(), stats).resolve(tables.palette, tables.air, columns);
        store.applyChunk(1, 1, columns, 3 * SECOND);
        RegionPage queued = store.pollUpload();
        Check.that(queued == page && store.pollUpload() == null, "one page queued");
        int[] rows = new int[Regions.TILES];
        queued.takeDirty(rows);
        Check.eq(1 << 1, rows[1], "tile 1,1");
        rows[1] = 0;
        Check.that(Arrays.stream(rows).allMatch(r -> r == 0), "and no other tile");
        Check.eq(Block.getId(Blocks.GOLD_BLOCK.defaultBlockState()), page.state[((16 + 7) << Regions.SHIFT) + 16 + 7], "the new block is the column's state");

        // Save, then read everything back in a second store: same terrain, same picture.
        store.flush();
        long bytes = 0;
        try (var files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                bytes += Files.size(f);
            }
        }
        System.out.println("  saved 4 regions (" + (48 * 48) + " chunks) in " + bytes / 1024 + " kB");
        PageStore reloaded = new PageStore(new RegionFiles(dir), 64, new Stats());
        reloaded.open(tables.palette, StateTables.build());
        for (long key : reloaded.diskIndex()) {
            reloaded.loadForDisplay(key, SECOND);
        }
        Check.eq(4, reloaded.pageCount(), "four regions reloaded");
        for (int rz = -1; rz <= 0; rz++) {
            for (int rx = -1; rx <= 0; rx++) {
                RegionPage a = store.pageIfLoaded(rx, rz);
                RegionPage b = reloaded.pageIfLoaded(rx, rz);
                Check.that(Arrays.equals(a.state, b.state) && Arrays.equals(a.height, b.height) && Arrays.equals(a.depth, b.depth),
                        "terrain of region " + rx + "," + rz);
                Check.that(Arrays.equals(a.pixels, b.pixels), "picture of region " + rx + "," + rz);
            }
        }

        if (imageFile != null) {
            BufferedImage image = new BufferedImage(2 * Regions.SIZE, 2 * Regions.SIZE, BufferedImage.TYPE_INT_RGB);
            for (int rz = -1; rz <= 0; rz++) {
                for (int rx = -1; rx <= 0; rx++) {
                    int[] pixels = reloaded.pageIfLoaded(rx, rz).pixels;
                    for (int z = 0; z < Regions.SIZE; z++) {
                        for (int x = 0; x < Regions.SIZE; x++) {
                            int abgr = pixels[(z << Regions.SHIFT) + x];
                            int rgb = (abgr & 0xFF) << 16 | (abgr & 0xFF00) | (abgr >> 16 & 0xFF);
                            image.setRGB((rx + 1) * Regions.SIZE + x, (rz + 1) * Regions.SIZE + z, rgb);
                        }
                    }
                }
            }
            ImageIO.write(image, "png", new File(imageFile));
            System.out.println("  wrote " + imageFile);
        }
    }

    static void drainUploads(PageStore store) {
        int[] rows = new int[Regions.TILES];
        for (RegionPage p; (p = store.pollUpload()) != null; ) {
            p.takeDirty(rows);
        }
    }
}
