package dev.lightmap.core;

/**
 * Per-block-state colour table, indexed by the game's global block-state id.
 *
 * <p>Built once per session from the block registry; the worker then colours pixels with plain
 * array reads. Swapping in a different colour source later (texture averages, biome tints) only
 * changes how this table is filled.
 */
public final class Palette {
    /** The state has a map colour: the downward scan stops on it. */
    public static final byte VISIBLE = 1;
    /** The state is drawn as its fluid and shaded by fluid depth rather than by slope. */
    public static final byte FLUID = 2;
    /** The state holds fluid (including waterlogged blocks): it counts towards fluid depth. */
    public static final byte HAS_FLUID = 4;
    /** 0xRRGGBB per state id. */
    public final int[] rgb;
    public final byte[] flags;

    public Palette(int[] rgb, byte[] flags) {
        if (rgb.length != flags.length) {
            throw new IllegalArgumentException("rgb and flags must have the same length");
        }
        this.rgb = rgb;
        this.flags = flags;
    }

    public int size() {
        return rgb.length;
    }

    public boolean has(int state, byte flag) {
        return state >= 0 && state < flags.length && (flags[state] & flag) != 0;
    }
}
