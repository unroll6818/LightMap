package dev.lightmap.core;

import java.util.Arrays;

/**
 * One 512x512 map page: the recorded terrain of a region and the pixels derived from it.
 *
 * <p>Thread ownership is fixed per field group:
 * <ul>
 *   <li>the raw layers ({@code state}, {@code height}, {@code depth}) belong to the map worker;
 *   <li>{@code pixels} is written by the worker and read by the client thread when it uploads. A
 *       read can race with a write to the same tile; the worker marks the tile dirty after
 *       writing, so a torn tile is always followed by a clean upload;
 *   <li>the dirty-tile bitmap is shared and guarded by the page's monitor;
 *   <li>{@code gpu} belongs to the client thread.
 * </ul>
 */
public final class RegionPage {
    /** Height value of a column nothing has been recorded for. */
    public static final short NO_DATA = Short.MIN_VALUE;

    public final int rx;
    public final int rz;
    public final long key;

    public final int[] state = new int[Regions.AREA];
    public final short[] height = new short[Regions.AREA];
    public final byte[] depth = new byte[Regions.AREA];

    /** ABGR, see {@link Shading}. */
    public final int[] pixels = new int[Regions.AREA];

    private final int[] dirtyRows = new int[Regions.TILES];
    private boolean queued;

    // Worker only: unsaved changes, and when the first of them was made.
    boolean modified;
    long modifiedAt;

    /** Client thread only: the texture this page is uploaded to, if any. */
    public Object gpu;
    /**
     * Time the page was last drawn or last had chunk data applied. Written by both threads without
     * synchronisation: it only steers eviction, where a stale value costs nothing.
     */
    public long keepAliveAt;
    /** Set by the worker once the page has left the store. */
    public volatile boolean removed;

    RegionPage(int rx, int rz) {
        this.rx = rx;
        this.rz = rz;
        this.key = Regions.key(rx, rz);
        Arrays.fill(height, NO_DATA);
        Arrays.fill(pixels, Shading.UNEXPLORED);
    }

    public boolean hasChunk(int tx, int tz) {
        return height[(tz << Regions.TILE_SHIFT << Regions.SHIFT) + (tx << Regions.TILE_SHIFT)] != NO_DATA;
    }

    /** @return true if the caller must put the page on the upload queue */
    synchronized boolean markTile(int tx, int tz) {
        dirtyRows[tz] |= 1 << tx;
        return enqueue();
    }

    /** @return true if the caller must put the page on the upload queue */
    synchronized boolean markAll() {
        Arrays.fill(dirtyRows, -1);
        return enqueue();
    }

    /**
     * Returns tiles that did not fit this tick's upload budget.
     *
     * @return true if the caller must put the page back on the upload queue
     */
    public synchronized boolean putBack(int[] rows) {
        boolean any = false;
        for (int z = 0; z < Regions.TILES; z++) {
            dirtyRows[z] |= rows[z];
            any |= rows[z] != 0;
        }
        return any && enqueue();
    }

    /** Moves the dirty set into {@code out} and clears it. Called by the uploader. */
    public synchronized void takeDirty(int[] out) {
        System.arraycopy(dirtyRows, 0, out, 0, Regions.TILES);
        Arrays.fill(dirtyRows, 0);
        queued = false;
    }

    private boolean enqueue() {
        if (queued) {
            return false;
        }
        queued = true;
        return true;
    }
}
