package dev.lightmap.core;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * On-disk form of a region page.
 *
 * <p>The file stores what was recorded, not what was drawn: block state, height and fluid depth
 * per column. Colours are derived again on load, so a saved map follows any later change to how
 * blocks are coloured. Block states are stored by name through a per-file palette, because the
 * numeric state ids are only stable within one game session.
 *
 * <pre>
 * int   magic "LMAP"        short version      short flags (0)
 * int   regionX             int   regionZ
 * deflate {
 *   byte[128]  chunk presence, bit (z * 32 + x)
 *   int        palette size, then that many UTF names
 *   short[]    palette index, 256 per present chunk
 *   short[]    height,        256 per present chunk
 *   byte[]     fluid depth,   256 per present chunk
 * }
 * </pre>
 */
public final class RegionCodec {
    /** Maps between the session's numeric block-state ids and stable names. */
    public interface StateNames {
        String nameOf(int stateId);

        /** @return the id for a name, or 0 (air) if this game does not know it */
        int idOf(String name);
    }

    static final int MAGIC = 0x4F435452;
    static final int VERSION = 1;
    private static final int PRESENCE_BYTES = Regions.TILES * Regions.TILES / 8;

    private RegionCodec() {
    }

    public static byte[] encode(RegionPage page, StateNames names) throws IOException {
        byte[] presence = new byte[PRESENCE_BYTES];
        int chunks = 0;
        Int2IntOpenHashMap local = new Int2IntOpenHashMap();
        local.defaultReturnValue(-1);
        IntArrayList order = new IntArrayList();

        for (int tz = 0; tz < Regions.TILES; tz++) {
            for (int tx = 0; tx < Regions.TILES; tx++) {
                if (!page.hasChunk(tx, tz)) {
                    continue;
                }
                int bit = tz * Regions.TILES + tx;
                presence[bit >> 3] |= (byte) (1 << (bit & 7));
                chunks++;
                for (int z = 0; z < Regions.TILE; z++) {
                    int row = rowStart(tx, tz, z);
                    for (int x = 0; x < Regions.TILE; x++) {
                        int id = page.state[row + x];
                        if (local.get(id) < 0) {
                            local.put(id, order.size());
                            order.add(id);
                        }
                    }
                }
            }
        }
        if (order.size() > 0xFFFF) {
            throw new IOException("region has " + order.size() + " distinct block states, more than the format holds");
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream(16 + chunks * 512);
        DataOutputStream head = new DataOutputStream(bytes);
        head.writeInt(MAGIC);
        head.writeShort(VERSION);
        head.writeShort(0);
        head.writeInt(page.rx);
        head.writeInt(page.rz);
        head.flush();

        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new DeflaterOutputStream(bytes, deflater, 8192), 1 << 16))) {
            out.write(presence);
            out.writeInt(order.size());
            for (int i = 0; i < order.size(); i++) {
                out.writeUTF(names.nameOf(order.getInt(i)));
            }
            for (int plane = 0; plane < 3; plane++) {
                for (int tz = 0; tz < Regions.TILES; tz++) {
                    for (int tx = 0; tx < Regions.TILES; tx++) {
                        if (!page.hasChunk(tx, tz)) {
                            continue;
                        }
                        for (int z = 0; z < Regions.TILE; z++) {
                            int row = rowStart(tx, tz, z);
                            for (int x = 0; x < Regions.TILE; x++) {
                                switch (plane) {
                                    case 0 -> out.writeShort(local.get(page.state[row + x]));
                                    case 1 -> out.writeShort(page.height[row + x]);
                                    default -> out.writeByte(page.depth[row + x]);
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            deflater.end();
        }
        return bytes.toByteArray();
    }

    /**
     * Fills the page's raw layers from a file. The page must be freshly created.
     *
     * @return false if the data is not a region file this version can read, or belongs to
     *     another region; the page is left untouched in that case
     */
    public static boolean decode(byte[] data, RegionPage page, StateNames names) throws IOException {
        if (data.length < 16) {
            return false;
        }
        DataInputStream head = new DataInputStream(new ByteArrayInputStream(data, 0, 16));
        if (head.readInt() != MAGIC || head.readUnsignedShort() != VERSION) {
            return false;
        }
        head.readUnsignedShort();
        if (head.readInt() != page.rx || head.readInt() != page.rz) {
            return false;
        }

        try (DataInputStream in = new DataInputStream(
                new InflaterInputStream(new ByteArrayInputStream(data, 16, data.length - 16)))) {
            byte[] presence = new byte[PRESENCE_BYTES];
            in.readFully(presence);
            int paletteSize = in.readInt();
            if (paletteSize < 0 || paletteSize > 0xFFFF) {
                throw new IOException("bad palette size " + paletteSize);
            }
            int[] palette = new int[paletteSize];
            for (int i = 0; i < paletteSize; i++) {
                palette[i] = names.idOf(in.readUTF());
            }

            // Decode into scratch first so a truncated file cannot leave the page half filled.
            int chunks = 0;
            for (byte b : presence) {
                chunks += Integer.bitCount(b & 0xFF);
            }
            int columns = chunks * ChunkColumns.COLUMNS;
            int[] state = new int[columns];
            short[] height = new short[columns];
            byte[] depth = new byte[columns];
            for (int i = 0; i < columns; i++) {
                int index = in.readUnsignedShort();
                if (index >= paletteSize) {
                    throw new IOException("palette index " + index + " out of range");
                }
                state[i] = palette[index];
            }
            for (int i = 0; i < columns; i++) {
                height[i] = in.readShort();
            }
            in.readFully(depth);

            int next = 0;
            for (int tz = 0; tz < Regions.TILES; tz++) {
                for (int tx = 0; tx < Regions.TILES; tx++) {
                    int bit = tz * Regions.TILES + tx;
                    if ((presence[bit >> 3] & 1 << (bit & 7)) == 0) {
                        continue;
                    }
                    for (int z = 0; z < Regions.TILE; z++) {
                        int row = rowStart(tx, tz, z);
                        System.arraycopy(state, next, page.state, row, Regions.TILE);
                        System.arraycopy(height, next, page.height, row, Regions.TILE);
                        System.arraycopy(depth, next, page.depth, row, Regions.TILE);
                        next += Regions.TILE;
                    }
                    // A present chunk must not read as missing, whatever the file said.
                    int first = rowStart(tx, tz, 0);
                    if (page.height[first] == RegionPage.NO_DATA) {
                        clearChunk(page, tx, tz);
                    }
                }
            }
        }
        return true;
    }

    private static void clearChunk(RegionPage page, int tx, int tz) {
        for (int z = 0; z < Regions.TILE; z++) {
            int row = rowStart(tx, tz, z);
            Arrays.fill(page.state, row, row + Regions.TILE, 0);
            Arrays.fill(page.height, row, row + Regions.TILE, RegionPage.NO_DATA);
            Arrays.fill(page.depth, row, row + Regions.TILE, (byte) 0);
        }
    }

    private static int rowStart(int tx, int tz, int z) {
        return (((tz << Regions.TILE_SHIFT) + z) << Regions.SHIFT) + (tx << Regions.TILE_SHIFT);
    }
}
