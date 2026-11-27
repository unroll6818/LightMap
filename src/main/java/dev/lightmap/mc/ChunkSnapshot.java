package dev.lightmap.mc;

import dev.lightmap.core.ChunkColumns;
import dev.lightmap.core.Palette;
import dev.lightmap.core.Stats;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.EnumSet;

/**
 * A private copy of the part of a chunk the map needs, taken on the client thread and read on the
 * worker.
 *
 * <p>Taking it is the only map work the client thread does per chunk, so it does as little as it
 * can: read the 256 surface heights, then copy the block-state containers of the few sections
 * around the surface. Copying a container is an array copy. Walking columns, looking states up
 * and colouring all happen later on the worker, against the copy.
 */
final class ChunkSnapshot {
    /**
     * How far below the lowest surface block the copy reaches. It bounds how deep water can be
     * measured and how far down the scan can look through blocks without a map colour, such as
     * glass.
     */
    private static final int DEPTH_MARGIN = 32;

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final PalettedContainer<BlockState>[] NO_SECTIONS = new PalettedContainer[0];

    final int cx;
    final int cz;
    /** World Y of the bottom of {@code sections[0]}. Nothing below it was copied. */
    final int floor;
    /**
     * Lowest Y at which a later block change could alter what this snapshot resolves to: the
     * floor of the copy, or the bottom of the world if any column is empty, since a block placed
     * anywhere in an empty column is seen from above.
     */
    final int watchFrom;
    /** Bottom-up; null where the section holds only air. */
    private final PalettedContainer<BlockState>[] sections;
    /** Per column, Y of the highest non-air block; below {@code floor} if the column is empty. */
    private final short[] tops;

    ChunkSnapshot(int cx, int cz, int floor, int watchFrom, PalettedContainer<BlockState>[] sections, short[] tops) {
        this.cx = cx;
        this.cz = cz;
        this.floor = floor;
        this.watchFrom = watchFrom;
        this.sections = sections;
        this.tops = tops;
    }

    /** Client thread. */
    static ChunkSnapshot capture(LevelChunk chunk, Stats stats) {
        return capture(chunk, stats, false);
    }

    private static boolean hasBlocks(LevelChunkSection[] sections) {
        for (LevelChunkSection section : sections) {
            if (!section.hasOnlyAir()) {
                return true;
            }
        }
        return false;
    }

    private static ChunkSnapshot capture(LevelChunk chunk, Stats stats, boolean primed) {
        int minY = chunk.getMinY();
        Heightmap surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE);
        short[] tops = new short[ChunkColumns.COLUMNS];
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        boolean anyEmpty = false;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int y = surface.getFirstAvailable(x, z) - 1;
                tops[z << 4 | x] = (short) y;
                highest = Math.max(highest, y);
                if (y >= minY) {
                    lowest = Math.min(lowest, y);
                } else {
                    // An empty column (open void) does not pull the copied range down to bedrock.
                    anyEmpty = true;
                }
            }
        }

        LevelChunkSection[] source = chunk.getSections();
        if (highest < minY && !primed && hasBlocks(source)) {
            // The server sent blocks but no surface heightmap. Vanilla always sends one; rebuild
            // it the way the game itself would on first use, then read it again.
            Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.WORLD_SURFACE));
            return capture(chunk, stats, true);
        }
        if (highest < minY || source.length == 0) {
            return new ChunkSnapshot(chunk.getPos().x(), chunk.getPos().z(), minY, minY, NO_SECTIONS, tops);
        }
        int first = Math.clamp((lowest - DEPTH_MARGIN - minY) >> 4, 0, source.length - 1);
        int last = Math.clamp((highest - minY) >> 4, first, source.length - 1);

        @SuppressWarnings({"unchecked", "rawtypes"})
        PalettedContainer<BlockState>[] copies = new PalettedContainer[last - first + 1];
        for (int i = first; i <= last; i++) {
            LevelChunkSection section = source[i];
            if (!section.hasOnlyAir()) {
                copies[i - first] = section.getStates().copy();
                stats.clientSectionsCopied++;
            }
        }
        int floor = minY + (first << 4);
        return new ChunkSnapshot(chunk.getPos().x(), chunk.getPos().z(), floor, anyEmpty ? minY : floor, copies, tops);
    }

    /**
     * Worker thread. Finds, for each column, the highest block with a map colour and, if that is
     * a fluid, how deep the fluid goes.
     */
    void resolve(Palette palette, int air, ChunkColumns out) {
        byte[] flags = palette.flags;
        int top = floor + (sections.length << 4) - 1;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int i = z << 4 | x;
                int y = Math.min(tops[i], top);
                int id = air;
                boolean found = false;
                for (; y >= floor; y--) {
                    id = stateId(x, y, z, air);
                    if (id < flags.length && (flags[id] & Palette.VISIBLE) != 0) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    // Recorded, but with nothing to draw: an empty column or the open void.
                    out.state[i] = air;
                    out.height[i] = (short) (floor - 1);
                    out.depth[i] = 0;
                    continue;
                }
                int depth = 0;
                if ((flags[id] & Palette.FLUID) != 0) {
                    depth = 1;
                    for (int below = y - 1; below >= floor && depth < 255; below--) {
                        int belowId = stateId(x, below, z, air);
                        if (belowId >= flags.length || (flags[belowId] & Palette.HAS_FLUID) == 0) {
                            break;
                        }
                        depth++;
                    }
                }
                out.state[i] = id;
                out.height[i] = (short) y;
                out.depth[i] = (byte) depth;
            }
        }
    }

    private int stateId(int x, int y, int z, int air) {
        PalettedContainer<BlockState> section = sections[(y - floor) >> 4];
        return section == null ? air : Block.getId(section.get(x, y & 15, z));
    }
}
