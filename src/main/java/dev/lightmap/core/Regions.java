package dev.lightmap.core;

/**
 * Coordinate layout shared by everything that touches map pages.
 *
 * <p>A region is 32x32 chunks = 512x512 blocks and is stored as one 512x512 page (one pixel per
 * block). A tile is one chunk = 16x16 pixels, the unit that is marked dirty and uploaded.
 */
public final class Regions {
    public static final int SHIFT = 9;
    public static final int SIZE = 1 << SHIFT;
    public static final int MASK = SIZE - 1;
    public static final int AREA = SIZE * SIZE;

    public static final int TILE_SHIFT = 4;
    public static final int TILE = 1 << TILE_SHIFT;
    /** Tiles (chunks) along one side of a region. */
    public static final int TILES = SIZE >> TILE_SHIFT;
    public static final int CHUNK_TO_REGION_SHIFT = SHIFT - TILE_SHIFT;

    private Regions() {
    }

    public static long key(int rx, int rz) {
        return ((long) rx << 32) | (rz & 0xFFFFFFFFL);
    }

    public static int keyX(long key) {
        return (int) (key >> 32);
    }

    public static int keyZ(long key) {
        return (int) key;
    }

    public static int ofBlock(int block) {
        return block >> SHIFT;
    }

    public static int ofChunk(int chunk) {
        return chunk >> CHUNK_TO_REGION_SHIFT;
    }

    /** Key of a chunk position, used for the per-chunk bookkeeping maps. */
    public static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
