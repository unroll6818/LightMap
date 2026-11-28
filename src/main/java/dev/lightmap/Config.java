package dev.lightmap;

/**
 * Start-up settings, read once from JVM system properties ({@code -Dlightmap.pages=128}). The
 * ones a player changes in the game are in {@link Settings}. Sizes are in real screen pixels, not
 * GUI-scaled units, so a value means the same thing at any GUI scale.
 */
public final class Config {
    /** Gap between the minimap and the top-left corner of the window, in screen pixels. */
    public static final int MINIMAP_MARGIN = Math.clamp(integer("lightmap.minimap.margin", 10), 0, 512);
    /** Screen pixels per block on the minimap. */
    public static final float MINIMAP_ZOOM = Math.clamp(number("lightmap.minimap.zoom", 1f), 0.25f, 16f);
    /** Keep the recorded map on disk between sessions. Off keeps it in memory only. */
    public static final boolean PERSIST = flag("lightmap.persist", true);
    /** Most region pages held in memory (about 2.8 MB each; on the GPU fifteen share a 16 MB atlas). */
    public static final int MAX_PAGES = Math.clamp(integer("lightmap.pages", 96), 16, 1024);
    /** Most pixel data sent to the GPU per client tick, in bytes. */
    public static final int UPLOAD_BYTES_PER_TICK = Math.clamp(integer("lightmap.uploadBytesPerTick", 1 << 20), 1 << 10, 1 << 26);
    /** Log work counters every five seconds. */
    public static final boolean STATS = flag("lightmap.stats", false);

    private Config() {
    }

    private static boolean flag(String key, boolean fallback) {
        String value = System.getProperty(key);
        return value == null ? fallback : Boolean.parseBoolean(value.trim());
    }

    private static int integer(String key, int fallback) {
        try {
            String value = System.getProperty(key);
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static float number(String key, float fallback) {
        try {
            String value = System.getProperty(key);
            float parsed = value == null ? fallback : Float.parseFloat(value.trim());
            return Float.isFinite(parsed) ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
