package dev.lightmap.core;

/**
 * Turns a page's dirty-tile bitmap into a short list of rectangles to upload.
 *
 * <p>The bitmap is one int per tile row, bit x set when tile (x, z) is dirty. Horizontal runs are
 * merged, and runs with the same extent in consecutive rows are merged vertically, so the usual
 * shapes (a strip of new chunks at the edge of view, a ring, a full page) come out as a handful of
 * upload calls rather than one per chunk.
 */
public final class DirtyTiles {
    /** Upper bound on the rectangles one page can produce: 16 runs in each of 32 rows. */
    public static final int MAX_RECTS = (Regions.TILES / 2) * Regions.TILES;

    private DirtyTiles() {
    }

    /**
     * @param rows dirty bitmap, left unchanged
     * @param out receives rectangles packed with {@link #pack}; needs room for {@link #MAX_RECTS}
     * @return number of rectangles written
     */
    public static int toRects(int[] rows, int[] out) {
        int count = 0;
        for (int z = 0; z < Regions.TILES; z++) {
            int bits = rows[z];
            while (bits != 0) {
                int x = Integer.numberOfTrailingZeros(bits);
                int w = Integer.numberOfTrailingZeros(~(bits >>> x));
                bits &= ~(mask(w) << x);

                // Grow a rectangle that ended on the previous row with exactly this extent.
                boolean extended = false;
                for (int k = count - 1; k >= 0; k--) {
                    int r = out[k];
                    if (x(r) == x && w(r) == w && z(r) + h(r) == z) {
                        out[k] = pack(x, z(r), w, h(r) + 1);
                        extended = true;
                        break;
                    }
                }
                if (!extended) {
                    out[count++] = pack(x, z, w, 1);
                }
            }
        }
        return count;
    }

    private static int mask(int bits) {
        return bits >= 32 ? -1 : (1 << bits) - 1;
    }

    public static int pack(int x, int z, int w, int h) {
        return x | z << 8 | w << 16 | h << 24;
    }

    public static int x(int rect) {
        return rect & 0xFF;
    }

    public static int z(int rect) {
        return rect >> 8 & 0xFF;
    }

    public static int w(int rect) {
        return rect >> 16 & 0xFF;
    }

    public static int h(int rect) {
        return rect >>> 24;
    }

    /** Sets the bits of a rectangle in a bitmap. */
    public static void set(int[] rows, int rect) {
        int bits = mask(w(rect)) << x(rect);
        for (int z = z(rect), end = z + h(rect); z < end; z++) {
            rows[z] |= bits;
        }
    }
}
