package dev.lightmap.core;

/**
 * Turns one recorded column into one pixel.
 *
 * <p>Pixels are packed as ABGR ints, so that written little-endian they are R, G, B, A bytes, the
 * layout an RGBA8 texture upload expects. Every pixel is opaque: unexplored areas get a fixed dark
 * colour rather than alpha 0, which keeps linear filtering free of dark fringes at the frontier.
 */
public final class Shading {
    public static final int UNEXPLORED = abgr(0x14, 0x15, 0x18);
    /** Explored, but nothing with a map colour was found in the column (open void, all glass). */
    public static final int VOID = abgr(0x00, 0x00, 0x00);

    private static final int MAX_SLOPE = 3;
    private static final int[] LAND = new int[2 * MAX_SLOPE + 1];
    private static final int[] FLUID = new int[32];

    static {
        // Relief: a column higher than its northern neighbour is lit, a lower one is in shade.
        // The end points match the vanilla map's brightest and normal-dark levels (255 and 180).
        for (int d = -MAX_SLOPE; d <= MAX_SLOPE; d++) {
            int v = d >= 0 ? 220 + d * 12 : 220 + d * 13;
            LAND[d + MAX_SLOPE] = Math.clamp(v, 0, 255);
        }
        // Fluids darken with depth and level off.
        for (int depth = 0; depth < FLUID.length; depth++) {
            FLUID[depth] = Math.clamp(261 - 6 * Math.max(depth, 1), 160, 255);
        }
    }

    private Shading() {
    }

    /**
     * @param north height of the column one block to the north, or {@link RegionPage#NO_DATA}
     */
    public static int pixel(Palette palette, int state, short height, int depth, short north) {
        if (height == RegionPage.NO_DATA) {
            return UNEXPLORED;
        }
        if (state < 0 || state >= palette.flags.length) {
            return VOID;
        }
        byte flags = palette.flags[state];
        if ((flags & Palette.VISIBLE) == 0) {
            return VOID;
        }
        int shade;
        if ((flags & Palette.FLUID) != 0) {
            shade = FLUID[Math.min(depth & 0xFF, FLUID.length - 1)];
        } else if (north == RegionPage.NO_DATA) {
            shade = LAND[MAX_SLOPE];
        } else {
            shade = LAND[Math.clamp(height - north, -MAX_SLOPE, MAX_SLOPE) + MAX_SLOPE];
        }
        int rgb = palette.rgb[state];
        int r = ((rgb >> 16 & 0xFF) * shade) / 255;
        int g = ((rgb >> 8 & 0xFF) * shade) / 255;
        int b = ((rgb & 0xFF) * shade) / 255;
        return abgr(r, g, b);
    }

    public static int abgr(int r, int g, int b) {
        return 0xFF000000 | b << 16 | g << 8 | r;
    }
}
