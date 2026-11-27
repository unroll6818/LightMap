package dev.lightmap.core;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The map pages of one world and dimension, and everything that happens to them off the client
 * thread: applying recorded chunks, colouring, loading, saving and eviction.
 *
 * <p>All methods in the first half run on the single map worker thread, which is what lets the raw
 * layers go without locks. The client thread only ever talks to the store through the two queues
 * at the bottom: pages with tiles to upload, and pages that were added or removed.
 */
public final class PageStore {
    private static final Logger LOG = LoggerFactory.getLogger("lightmap");

    /** How long a page may hold unsaved changes before it is written. */
    static final long SAVE_DELAY_NS = 10_000_000_000L;
    /** How long a page stays in memory after it was last drawn or changed. */
    static final long KEEP_NS = 30_000_000_000L;
    private static final long EVICT_SCAN_NS = 2_000_000_000L;
    private static final int MAX_WRITE_FAILURES = 5;

    private final RegionFiles files;
    private final int maxPages;
    public final Stats stats;

    private Palette palette;
    private RegionCodec.StateNames names;

    private final Long2ObjectOpenHashMap<RegionPage> pages = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet onDisk = new LongOpenHashSet();
    private long lastEvictScan;
    private int writeFailures;
    private boolean writesDisabled;

    private final ConcurrentLinkedQueue<RegionPage> uploads = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<RegionPage> events = new ConcurrentLinkedQueue<>();
    private volatile long[] diskIndex = new long[0];

    /**
     * @param files where regions are stored, or null to keep the map in memory only
     * @param maxPages upper bound on pages held in memory
     */
    public PageStore(RegionFiles files, int maxPages, Stats stats) {
        this.files = files;
        this.maxPages = Math.max(4, maxPages);
        this.stats = stats;
    }

    // ------------------------------------------------------------------ worker thread

    /** First call on the worker: supplies the colour table and lists what is already on disk. */
    public void open(Palette palette, RegionCodec.StateNames names) {
        this.palette = palette;
        this.names = names;
        if (files == null) {
            return;
        }
        try {
            for (long key : files.list()) {
                onDisk.add(key);
            }
        } catch (IOException e) {
            LOG.warn("[lightmap] could not list saved regions in {}", files.dir(), e);
        }
        diskIndex = onDisk.toLongArray();
    }

    public Palette palette() {
        return palette;
    }

    /** Records one chunk. Does nothing beyond a compare when the chunk is unchanged. */
    public void applyChunk(int cx, int cz, ChunkColumns columns, long now) {
        RegionPage page = page(Regions.ofChunk(cx), Regions.ofChunk(cz), now, true);
        page.keepAliveAt = now;
        int ox = (cx & Regions.TILES - 1) << Regions.TILE_SHIFT;
        int oz = (cz & Regions.TILES - 1) << Regions.TILE_SHIFT;

        boolean changed = false;
        for (int z = 0; z < Regions.TILE; z++) {
            int row = ((oz + z) << Regions.SHIFT) + ox;
            int col = z << Regions.TILE_SHIFT;
            for (int x = 0; x < Regions.TILE; x++) {
                int i = row + x;
                int j = col + x;
                if (page.state[i] != columns.state[j] || page.height[i] != columns.height[j] || page.depth[i] != columns.depth[j]) {
                    page.state[i] = columns.state[j];
                    page.height[i] = columns.height[j];
                    page.depth[i] = columns.depth[j];
                    changed = true;
                }
            }
        }
        stats.workerChunksApplied++;
        if (!changed) {
            stats.workerChunksUnchanged++;
            return;
        }
        if (!page.modified) {
            page.modified = true;
            page.modifiedAt = now;
        }

        recolourAndMark(page, ox, oz, Regions.TILE, Regions.TILE);
        // The row just south of the chunk is shaded against the chunk's last row.
        if (oz + Regions.TILE < Regions.SIZE) {
            recolourAndMark(page, ox, oz + Regions.TILE, Regions.TILE, 1);
        } else {
            RegionPage south = pages.get(Regions.key(page.rx, page.rz + 1));
            if (south != null) {
                recolourAndMark(south, ox, 0, Regions.TILE, 1);
            }
        }
    }

    /** Brings a saved region into memory because something wants to draw it. */
    public void loadForDisplay(long key, long now) {
        RegionPage page = pages.get(key);
        if (page != null) {
            page.keepAliveAt = now;
            return;
        }
        page(Regions.keyX(key), Regions.keyZ(key), now, false);
    }

    /**
     * Background upkeep, called when the worker has nothing more urgent: saves one page that has
     * had unsaved changes for long enough, or drops pages nobody has needed for a while.
     *
     * @return true if it saved something and should be called again soon
     */
    public boolean idle(long now) {
        if (palette == null) {
            return false;
        }
        RegionPage oldest = null;
        for (RegionPage p : pages.values()) {
            if (p.modified && now - p.modifiedAt >= SAVE_DELAY_NS && (oldest == null || p.modifiedAt < oldest.modifiedAt)) {
                oldest = p;
            }
        }
        if (oldest != null) {
            save(oldest);
            return true;
        }
        if (now - lastEvictScan >= EVICT_SCAN_NS) {
            lastEvictScan = now;
            if (files != null && !writesDisabled) {
                RegionPage[] snapshot = pages.values().toArray(new RegionPage[0]);
                for (RegionPage p : snapshot) {
                    if (!p.modified && now - p.keepAliveAt > KEEP_NS) {
                        evict(p);
                    }
                }
            }
            trim(null);
        }
        return false;
    }

    /** Writes every page with unsaved changes. */
    public void flush() {
        for (RegionPage p : pages.values()) {
            if (p.modified) {
                save(p);
            }
        }
    }

    /** Saves everything and lets go of all pages. The store must not be used afterwards. */
    public void close() {
        flush();
        for (RegionPage p : pages.values()) {
            p.removed = true;
            events.add(p);
        }
        pages.clear();
    }

    public int pageCount() {
        return pages.size();
    }

    /** Test and debug access; worker thread only. */
    public RegionPage pageIfLoaded(int rx, int rz) {
        return pages.get(Regions.key(rx, rz));
    }

    private RegionPage page(int rx, int rz, long now, boolean createIfNew) {
        long key = Regions.key(rx, rz);
        RegionPage p = pages.get(key);
        if (p != null) {
            return p;
        }
        boolean saved = files != null && onDisk.contains(key);
        if (!saved && !createIfNew) {
            return null;
        }
        p = new RegionPage(rx, rz);
        p.keepAliveAt = now;
        boolean loaded = saved && read(p);
        pages.put(key, p);
        stats.workerPagesCreated++;
        events.add(p);
        if (loaded) {
            stats.workerPagesLoaded++;
            recolour(p, 0, 0, Regions.SIZE, Regions.SIZE);
            if (p.markAll()) {
                uploads.add(p);
            }
            // The page below shades its first row against this page's last row.
            RegionPage south = pages.get(Regions.key(rx, rz + 1));
            if (south != null) {
                recolourAndMark(south, 0, 0, Regions.SIZE, 1);
            }
        }
        trim(p);
        return p;
    }

    private boolean read(RegionPage p) {
        try {
            byte[] data = files.read(p.key);
            if (data == null) {
                return false;
            }
            if (!RegionCodec.decode(data, p, names)) {
                LOG.warn("[lightmap] region {},{} in {} is not readable by this version; it will be recorded again", p.rx, p.rz, files.dir());
                return false;
            }
            return true;
        } catch (IOException | RuntimeException e) {
            LOG.warn("[lightmap] could not read region {},{} in {}", p.rx, p.rz, files.dir(), e);
            return false;
        }
    }

    private void save(RegionPage p) {
        // Cleared first: a page that cannot be written is not retried until it changes again.
        p.modified = false;
        if (files == null || writesDisabled) {
            return;
        }
        try {
            byte[] data = RegionCodec.encode(p, names);
            files.write(p.key, data);
            writeFailures = 0;
            stats.workerPagesSaved++;
            stats.workerBytesSaved += data.length;
            if (onDisk.add(p.key)) {
                diskIndex = onDisk.toLongArray();
            }
        } catch (IOException | RuntimeException e) {
            if (++writeFailures >= MAX_WRITE_FAILURES) {
                writesDisabled = true;
                LOG.error("[lightmap] giving up on saving the map to {} after repeated failures; it stays in memory for this session", files.dir(), e);
            } else {
                LOG.warn("[lightmap] could not save region {},{} to {}", p.rx, p.rz, files.dir(), e);
            }
        }
    }

    /**
     * Keeps the page count under the cap by dropping the pages that went unused the longest.
     *
     * @param keep a page that must survive, typically the one just created
     */
    private void trim(RegionPage keep) {
        while (pages.size() > maxPages) {
            RegionPage oldest = null;
            for (RegionPage p : pages.values()) {
                if (p != keep && (oldest == null || p.keepAliveAt < oldest.keepAliveAt)) {
                    oldest = p;
                }
            }
            if (oldest == null) {
                return;
            }
            if (oldest.modified) {
                save(oldest);
            }
            evict(oldest);
        }
    }

    private void evict(RegionPage p) {
        pages.remove(p.key);
        p.removed = true;
        events.add(p);
        stats.workerPagesEvicted++;
    }

    /** Recolours a rectangle tile by tile and queues the tiles whose pixels changed. */
    private void recolourAndMark(RegionPage p, int x0, int z0, int w, int h) {
        int tx1 = (x0 + w - 1) >> Regions.TILE_SHIFT;
        int tz1 = (z0 + h - 1) >> Regions.TILE_SHIFT;
        for (int tz = z0 >> Regions.TILE_SHIFT; tz <= tz1; tz++) {
            for (int tx = x0 >> Regions.TILE_SHIFT; tx <= tx1; tx++) {
                int ax = Math.max(x0, tx << Regions.TILE_SHIFT);
                int az = Math.max(z0, tz << Regions.TILE_SHIFT);
                int bx = Math.min(x0 + w, (tx + 1) << Regions.TILE_SHIFT);
                int bz = Math.min(z0 + h, (tz + 1) << Regions.TILE_SHIFT);
                if (recolour(p, ax, az, bx - ax, bz - az)) {
                    stats.workerTilesMarked++;
                    if (p.markTile(tx, tz)) {
                        uploads.add(p);
                    }
                }
            }
        }
    }

    /** @return true if any pixel in the rectangle changed */
    private boolean recolour(RegionPage p, int x0, int z0, int w, int h) {
        RegionPage north = z0 == 0 ? pages.get(Regions.key(p.rx, p.rz - 1)) : null;
        int lastRow = (Regions.SIZE - 1) << Regions.SHIFT;
        boolean changed = false;
        for (int z = z0; z < z0 + h; z++) {
            int row = z << Regions.SHIFT;
            for (int x = x0; x < x0 + w; x++) {
                int i = row + x;
                short n;
                if (z > 0) {
                    n = p.height[i - Regions.SIZE];
                } else {
                    n = north == null ? RegionPage.NO_DATA : north.height[lastRow + x];
                }
                int pixel = Shading.pixel(palette, p.state[i], p.height[i], p.depth[i], n);
                if (p.pixels[i] != pixel) {
                    p.pixels[i] = pixel;
                    changed = true;
                }
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------ client thread

    /** Next page with tiles waiting to be uploaded, or null. */
    public RegionPage pollUpload() {
        return uploads.poll();
    }

    /** Puts a page back after {@link RegionPage#putBack} asked for it. */
    public void requeueUpload(RegionPage page) {
        uploads.add(page);
    }

    /**
     * Next page that entered or left the store, in order. A page that left has
     * {@link RegionPage#removed} set.
     */
    public RegionPage pollEvent() {
        return events.poll();
    }

    /**
     * Keys of the regions that have a file. A new array is published whenever the set grows, so
     * callers can compare references to see whether it changed.
     */
    public long[] diskIndex() {
        return diskIndex;
    }
}
