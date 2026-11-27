package dev.lightmap.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The named markers the player put on the map of one world and dimension.
 *
 * <p>The list is changed only by the player (on the client thread) and once by the worker when
 * the saved markers are read. Drawing reads {@link #all()}, a snapshot array that is replaced, never
 * changed, so a frame can walk it without a lock or an iterator.
 *
 * <p>Markers are kept in their own small text file, apart from the region files, so the region
 * format is untouched. The file starts with a version line; each further line is one marker:
 * {@code x<TAB>z<TAB>flags<TAB>name}, where flags is 1 for "shown on the minimap" plus 2 for
 * "name shown on the minimap". Version 1 files, which only knew 0 and 1, are still read.
 */
public final class Markers {
    public static final int MAX_NAME = 32;
    private static final String HEADER = "lightmap-markers 2";
    private static final String HEADER_V1 = "lightmap-markers 1";

    /**
     * A marker at a block column. {@code nameOnMinimap} only matters for a marker that is on the
     * minimap at all.
     */
    public record Marker(int x, int z, String name, boolean onMinimap, boolean nameOnMinimap) {
        public Marker(int x, int z, String name, boolean onMinimap) {
            this(x, z, name, onMinimap, false);
        }
    }

    private volatile Marker[] all = new Marker[0];

    /** Every marker; the array must not be changed. */
    public Marker[] all() {
        return all;
    }

    public synchronized void add(Marker marker) {
        Marker[] now = Arrays.copyOf(all, all.length + 1);
        now[now.length - 1] = marker;
        all = now;
    }

    /** Replaces a marker with another, or removes it if {@code replacement} is null. */
    public synchronized void replace(Marker old, Marker replacement) {
        Marker[] current = all;
        for (int i = 0; i < current.length; i++) {
            if (current[i] == old) {
                List<Marker> now = new ArrayList<>(Arrays.asList(current));
                if (replacement == null) {
                    now.remove(i);
                } else {
                    now.set(i, replacement);
                }
                all = now.toArray(new Marker[0]);
                return;
            }
        }
    }

    /** Puts markers read from disk in front of any the player placed before the read finished. */
    public synchronized void addLoaded(List<Marker> loaded) {
        List<Marker> now = new ArrayList<>(loaded);
        now.addAll(Arrays.asList(all));
        all = now.toArray(new Marker[0]);
    }

    public static byte[] encode(Marker[] markers) {
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        for (Marker m : markers) {
            out.append(m.x()).append('\t').append(m.z()).append('\t').append((m.onMinimap() ? 1 : 0) | (m.nameOnMinimap() ? 2 : 0)).append('\t');
            for (int i = 0; i < m.name().length(); i++) {
                char c = m.name().charAt(i);
                switch (c) {
                    case '\\' -> out.append("\\\\");
                    case '\t' -> out.append("\\t");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    default -> out.append(c);
                }
            }
            out.append('\n');
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Reads a marker file. A line that cannot be read is skipped rather than costing the player
     * every other marker.
     *
     * @throws IOException if the file is not a marker file of a known version
     */
    public static List<Marker> decode(byte[] data) throws IOException {
        BufferedReader in = new BufferedReader(new StringReader(new String(data, StandardCharsets.UTF_8)));
        String header = in.readLine();
        if (!HEADER.equals(header) && !HEADER_V1.equals(header)) {
            throw new IOException("not a LightMap marker file of version 1 or 2");
        }
        int maxFlags = HEADER.equals(header) ? 3 : 1;
        List<Marker> markers = new ArrayList<>();
        for (String line; (line = in.readLine()) != null; ) {
            String[] parts = line.split("\t", 4);
            if (parts.length != 4 || parts[2].length() != 1 || parts[2].charAt(0) < '0' || parts[2].charAt(0) > '0' + maxFlags) {
                continue;
            }
            int flags = parts[2].charAt(0) - '0';
            try {
                markers.add(new Marker(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), unescape(parts[3]), (flags & 1) != 0, (flags & 2) != 0));
            } catch (NumberFormatException ignored) {
                // Skipped, as above.
            }
        }
        return markers;
    }

    private static String unescape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                out.append(switch (next) {
                    case 't' -> '\t';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    default -> next;
                });
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
