package dev.lightmap.core;

/**
 * The 256 resolved columns of one chunk: what the map records per block column.
 *
 * <p>Index is {@code z << 4 | x}. {@code state} is the global id of the highest block state that
 * has a map colour, {@code height} its Y, and {@code depth} the number of fluid blocks from there
 * down to the first non-fluid block (0 for land).
 */
public final class ChunkColumns {
    public static final int COLUMNS = Regions.TILE * Regions.TILE;

    public final int[] state = new int[COLUMNS];
    public final short[] height = new short[COLUMNS];
    public final byte[] depth = new byte[COLUMNS];
}
