package dev.lightmap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * The settings that can be changed while the game runs. They are kept in
 * {@code config/lightmap.properties} and take effect on the next frame.
 *
 * <p>A JVM system property of the same name ({@code -Dlightmap.minimap.opacity=50}) wins over the
 * file and is never written back to it, so a benchmark run can pin a value without touching what
 * the player chose. A setting pinned that way is shown locked in the settings screen.
 *
 * <p>The fields are plain ints and booleans written from the client thread only; the minimap reads
 * them once per frame.
 */
public final class Settings {
    public static final String MINIMAP = "lightmap.minimap";
    public static final String OPACITY = "lightmap.minimap.opacity";
    public static final String SIZE = "lightmap.minimap.size";
    public static final String ROTATE = "lightmap.minimap.rotate";
    public static final String DISTINCT_MARKERS = "lightmap.minimap.markers.distinct";

    public static final int SIZE_MIN = 64;
    public static final int SIZE_MAX = 1024;
    public static final int SIZE_STEP = 8;
    public static final int OPACITY_STEP = 5;

    private static final Logger LOG = LoggerFactory.getLogger("lightmap");

    private static boolean minimap = true;
    private static int opacity = 100;
    private static int size = 368;
    private static boolean rotate = true;
    private static boolean distinctMarkers = true;
    private static Path file;

    private Settings() {
    }

    /** Draw the minimap. The map is recorded either way. */
    public static boolean minimap() {
        return minimap;
    }

    /** How solid the minimap is drawn, 0 (invisible) to 100 percent. */
    public static int opacity() {
        return opacity;
    }

    /** Side of the minimap square in screen pixels (not GUI-scaled). */
    public static int size() {
        return size;
    }

    /** Turn the minimap so the view direction is up; otherwise north is up. */
    public static boolean rotate() {
        return rotate;
    }

    /** Draw minimap markers larger and outlined; otherwise small and plain. */
    public static boolean distinctMarkers() {
        return distinctMarkers;
    }

    public static void setDistinctMarkers(boolean value) {
        distinctMarkers = value;
    }

    public static void setMinimap(boolean value) {
        minimap = value;
    }

    public static void setOpacity(int value) {
        opacity = Math.clamp(value, 0, 100);
    }

    public static void setSize(int value) {
        size = Math.clamp(value, SIZE_MIN, SIZE_MAX);
    }

    public static void setRotate(boolean value) {
        rotate = value;
    }

    /** Whether a setting is fixed by a JVM system property for this run. */
    public static boolean pinned(String key) {
        return System.getProperty(key) != null;
    }

    /** Reads the file, then lets system properties override it. A missing or broken file means defaults. */
    public static void load(Path configFile) {
        file = configFile;
        Properties stored = new Properties();
        if (Files.isRegularFile(configFile)) {
            try (Reader in = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
                stored.load(in);
            } catch (IOException | IllegalArgumentException e) {
                LOG.warn("[lightmap] could not read {}, using defaults: {}", configFile, e.toString());
                stored.clear();
            }
        }
        setMinimap(flag(stored, MINIMAP, true));
        setOpacity(integer(stored, OPACITY, 100));
        setSize(integer(stored, SIZE, 368));
        setRotate(flag(stored, ROTATE, true));
        setDistinctMarkers(flag(stored, DISTINCT_MARKERS, true));
    }

    /**
     * Writes the settings to the file given to {@link #load}. A pinned setting keeps the value the
     * file had, so a benchmark's override never becomes the player's choice.
     */
    public static void save() {
        Path target = file;
        if (target == null) {
            return;
        }
        Properties previous = new Properties();
        if (Files.isRegularFile(target)) {
            try (Reader in = Files.newBufferedReader(target, StandardCharsets.UTF_8)) {
                previous.load(in);
            } catch (IOException | IllegalArgumentException e) {
                previous.clear();
            }
        }
        Properties out = new Properties();
        put(out, previous, MINIMAP, Boolean.toString(minimap));
        put(out, previous, OPACITY, Integer.toString(opacity));
        put(out, previous, SIZE, Integer.toString(size));
        put(out, previous, ROTATE, Boolean.toString(rotate));
        put(out, previous, DISTINCT_MARKERS, Boolean.toString(distinctMarkers));
        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
            Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                out.store(writer, "LightMap settings");
            }
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.warn("[lightmap] could not save {}: {}", target, e.toString());
        }
    }

    private static void put(Properties out, Properties previous, String key, String current) {
        if (!pinned(key)) {
            out.setProperty(key, current);
        } else if (previous.getProperty(key) != null) {
            out.setProperty(key, previous.getProperty(key));
        }
    }

    private static String raw(Properties stored, String key) {
        String value = System.getProperty(key);
        return value != null ? value : stored.getProperty(key);
    }

    private static boolean flag(Properties stored, String key, boolean fallback) {
        String value = raw(stored, key);
        if (value == null) {
            return fallback;
        }
        value = value.trim();
        return value.equalsIgnoreCase("true") || (!value.equalsIgnoreCase("false") && fallback);
    }

    private static int integer(Properties stored, String key, int fallback) {
        String value = raw(stored, key);
        try {
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
