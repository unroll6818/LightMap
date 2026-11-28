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
 * {@code x<TAB>z<TAB>flags<TAB>colour<TAB>name}, where flags is 1 for "shown on the minimap"
 * plus 2 for "name shown on the minimap" and colour is an index into {@link #COLORS}. Version 2
 * files (no colour) and version 1 files (no colour, flags 0 and 1 only) are still read.
 */
public final class Markers {
    public static final int MAX_NAME = 32;
    private static final String HEADER = "lightmap-markers 3";
    private static final String HEADER_V2 = "lightmap-markers 2";
    private static final String HEADER_V1 = "lightmap-markers 1";

    /**
     * The colours a marker can have, as RGB: strong, saturated ones that stand out on terrain.
     * A marker stores the index, so the order is part of the file format: add at the end only.
     */
    public static final int[] COLORS = {
            0xFFD500, // yellow, the default
            0xFF2B2B, // red
            0xFF7A00, // orange
            0x12E03A, // green
            0x00D5FF, // cyan
            0x2F6BFF, // blue
            0xB14CFF, // purple
            0xFF4FD8, // pink
            0xFFFFFF, // white
    };

    /**
     * A marker at a block column. {@code nameOnMinimap} only matters for a marker that is on the
     * minimap at all.
     */
    public record Marker(int x, int z, String name, boolean onMinimap, boolean nameOnMinimap, int color) {
        public Marker {
            if (color < 0 || color >= COLORS.length) {
                color = 0;
            }
        }

        public Marker(int x, int z, String name, boolean onMinimap, boolean nameOnMinimap) {
            this(x, z, name, onMinimap, nameOnMinimap, 0);
        }

        public Marker(int x, int z, String name, boolean onMinimap) {
            this(x, z, name, onMinimap, false, 0);
        }

        /** This marker's colour as opaque ARGB. */
        public int argb() {
            return 0xFF000000 | COLORS[color];
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
            out.append(m.color()).append('\t');
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
        boolean colored = HEADER.equals(header);
        if (!colored && !HEADER_V2.equals(header) && !HEADER_V1.equals(header)) {
            throw new IOException("not a LightMap marker file of version 1 to 3");
        }
        int maxFlags = HEADER_V1.equals(header) ? 1 : 3;
        int fields = colored ? 5 : 4;
        List<Marker> markers = new ArrayList<>();
        for (String line; (line = in.readLine()) != null; ) {
            String[] parts = line.split("\t", fields);
            if (parts.length != fields || parts[2].length() != 1 || parts[2].charAt(0) < '0' || parts[2].charAt(0) > '0' + maxFlags) {
                continue;
            }
            int flags = parts[2].charAt(0) - '0';
            try {
                // A colour this version does not know (a file from a newer one) becomes the default.
                int color = colored ? Integer.parseInt(parts[3]) : 0;
                markers.add(new Marker(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), unescape(parts[fields - 1]),
                        (flags & 1) != 0, (flags & 2) != 0, color));
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
