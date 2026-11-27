package dev.lightmap.mc;

import dev.lightmap.Config;
import dev.lightmap.core.ChunkColumns;
import dev.lightmap.core.MapWorker;
import dev.lightmap.core.Markers;
import dev.lightmap.core.PageStore;
import dev.lightmap.core.RegionFiles;
import dev.lightmap.core.RegionPage;
import dev.lightmap.core.Regions;
import dev.lightmap.core.Stats;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The map of the level the client is currently in. Lives from the moment the level becomes
 * current until it is replaced or left.
 *
 * <p>Everything here is driven by events. A chunk is copied when it loads; a block change marks
 * its chunk for one fresh copy at the end of the tick, and only if the change is high enough to
 * be seen from above; uploads happen once per tick for the tiles the worker actually changed. A
 * frame in which nothing happened does no map work beyond drawing the quads.
 */
public final class MapSession {
    private static final Logger LOG = LoggerFactory.getLogger("lightmap");
    private static final int RECAPTURES_PER_TICK = 16;
    private static final long REQUEST_AGAIN_NS = 2_000_000_000L;
    private static final int NOT_CAPTURED = Integer.MAX_VALUE;

    public final ClientLevel level;
    /** Shared with the store: the client thread and the worker each write only their own counters. */
    public final Stats stats;
    /** The player's markers; changed on the client thread, see {@link #markersChanged()}. */
    public final Markers markers = new Markers();
    private final PageStore store;
    private final RegionFiles files;
    private final MapWorker worker;

    // Client thread.
    private final Long2ObjectOpenHashMap<RegionPage> pages = new Long2ObjectOpenHashMap<>();
    private final Long2IntOpenHashMap watchFrom = new Long2IntOpenHashMap();
    private final LongOpenHashSet changedChunks = new LongOpenHashSet();
    private final LongOpenHashSet savedRegions = new LongOpenHashSet();
    private final Long2LongOpenHashMap requestedAt = new Long2LongOpenHashMap();
    private long[] savedRegionsSource;

    // Worker thread.
    private final ChunkColumns columns = new ChunkColumns();
    private StateTables tables;

    private MapSession(ClientLevel level, RegionFiles files, PageStore store, MapWorker worker) {
        this.level = level;
        this.files = files;
        this.store = store;
        this.stats = store.stats;
        this.worker = worker;
        this.watchFrom.defaultReturnValue(NOT_CAPTURED);
    }

    public static MapSession open(Minecraft minecraft, ClientLevel level, MapWorker worker) {
        RegionFiles files = Config.PERSIST ? new RegionFiles(directory(minecraft, level)) : null;
        MapSession session = new MapSession(level, files, new PageStore(files, Config.MAX_PAGES, new Stats()), worker);
        worker.submit(session::openOnWorker);
        worker.setIdle(() -> session.store.idle(System.nanoTime()));
        return session;
    }

    /** One directory per world and dimension, under {@code <game dir>/lightmap}. */
    private static Path directory(Minecraft minecraft, ClientLevel level) {
        String world;
        IntegratedServer local = minecraft.getSingleplayerServer();
        ServerData remote = minecraft.getCurrentServer();
        if (minecraft.isLocalServer() && local != null) {
            Path root = local.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            world = "local-" + safe(root.getFileName() == null ? "world" : root.getFileName().toString());
        } else if (remote != null) {
            world = "server-" + safe(remote.ip);
        } else {
            world = "server-unknown";
        }
        String dimension = safe(level.dimension().identifier().toString());
        return minecraft.gameDirectory.toPath().resolve("lightmap").resolve(world).resolve(dimension);
    }

    private static String safe(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length() && out.length() < 80; i++) {
            char c = name.charAt(i);
            boolean plain = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '_' || c == '.';
            out.append(plain ? c : '_');
        }
        // Never a relative path component, whatever a server chose to call itself.
        String cleaned = out.toString().replaceAll("^\\.+", "_");
        return cleaned.isEmpty() ? "_" : cleaned;
    }

    // ------------------------------------------------------------------ worker thread

    private void openOnWorker() {
        try {
            StateTables built = StateTables.build();
            store.open(built.palette, built);
            tables = built;
        } catch (RuntimeException e) {
            LOG.error("[lightmap] could not set up the map for this world; nothing will be recorded", e);
        }
        loadMarkersOnWorker();
    }

    private void loadMarkersOnWorker() {
        if (files == null) {
            return;
        }
        try {
            byte[] data = files.readMarkers();
            if (data != null) {
                markers.addLoaded(Markers.decode(data));
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("[lightmap] could not read the markers in {}", files.dir(), e);
        }
    }

    private void saveMarkersOnWorker() {
        if (files == null) {
            return;
        }
        try {
            files.writeMarkers(Markers.encode(markers.all()));
        } catch (IOException | RuntimeException e) {
            LOG.warn("[lightmap] could not save the markers in {}", files.dir(), e);
        }
    }

    private void applyOnWorker(ChunkSnapshot snapshot) {
        if (tables == null) {
            return;
        }
        long started = System.nanoTime();
        snapshot.resolve(tables.palette, tables.air, columns);
        store.applyChunk(snapshot.cx, snapshot.cz, columns, started);
        stats.workerApplyNanos += System.nanoTime() - started;
    }

    // ------------------------------------------------------------------ client thread

    public void chunkLoaded(LevelChunk chunk) {
        capture(chunk);
    }

    public void chunkUnloaded(LevelChunk chunk) {
        long key = Regions.chunkKey(chunk.getPos().x(), chunk.getPos().z());
        watchFrom.remove(key);
        changedChunks.remove(key);
    }

    /**
     * A block changed. Only a change at or above the lowest Y the chunk's last copy looked at can
     * alter what the map shows, so anything deeper (mining, caves, redstone underground) is
     * dropped here for the price of one hash lookup.
     */
    public void blockChanged(BlockPos pos) {
        stats.clientBlockEvents++;
        long key = Regions.chunkKey(pos.getX() >> 4, pos.getZ() >> 4);
        if (pos.getY() >= watchFrom.get(key)) {
            stats.clientBlockEventsKept++;
            changedChunks.add(key);
        }
    }

    /** Once per client tick. */
    public void tick() {
        recaptureChanged();
        drainEvents();
        GpuPages.upload(store, stats, Config.UPLOAD_BYTES_PER_TICK);
    }

    /**
     * The player added, changed or removed a marker: the file is written in the background. Tasks
     * run in order, so the last save writes the latest list, and the read at start-up always comes
     * before any save.
     */
    public void markersChanged() {
        worker.submit(this::saveMarkersOnWorker);
    }

    /** The page of a region if it is in memory. */
    public RegionPage page(long regionKey) {
        return pages.get(regionKey);
    }

    /**
     * Says that a view would like to draw a region that is not in memory. If the region was saved
     * earlier it is loaded in the background and turns up through the event queue.
     */
    public void want(long regionKey, long now) {
        if (!savedRegions.contains(regionKey)) {
            return;
        }
        if (requestedAt.containsKey(regionKey) && now - requestedAt.get(regionKey) < REQUEST_AGAIN_NS) {
            return;
        }
        requestedAt.put(regionKey, now);
        worker.submit(() -> store.loadForDisplay(regionKey, System.nanoTime()));
    }

    /** Saves in the background and frees the textures. The session must not be used afterwards. */
    public void close() {
        // A page is announced before its first upload is queued, so after this every page that
        // can own a texture is in the map (or was already closed as removed).
        drainEvents();
        for (RegionPage page : pages.values()) {
            GpuPages.close(page, stats);
        }
        pages.clear();
        watchFrom.clear();
        changedChunks.clear();
        worker.submit(store::close);
    }

    public String statsSummary() {
        return stats.summary() + " | pages in memory=" + pages.size();
    }

    private void capture(LevelChunk chunk) {
        long started = System.nanoTime();
        ChunkSnapshot snapshot = ChunkSnapshot.capture(chunk, stats);
        watchFrom.put(Regions.chunkKey(snapshot.cx, snapshot.cz), snapshot.watchFrom);
        stats.clientChunksCaptured++;
        stats.clientCaptureNanos += System.nanoTime() - started;
        worker.submit(() -> applyOnWorker(snapshot));
    }

    private void recaptureChanged() {
        if (changedChunks.isEmpty()) {
            return;
        }
        int budget = RECAPTURES_PER_TICK;
        LongIterator it = changedChunks.iterator();
        while (it.hasNext() && budget > 0) {
            long key = it.nextLong();
            it.remove();
            LevelChunk chunk = level.getChunkSource().getChunk(Regions.keyX(key), Regions.keyZ(key), false);
            if (chunk != null) {
                capture(chunk);
                budget--;
            }
        }
    }

    private void drainEvents() {
        for (RegionPage page; (page = store.pollEvent()) != null; ) {
            if (page.removed) {
                if (pages.get(page.key) == page) {
                    pages.remove(page.key);
                }
                GpuPages.close(page, stats);
            } else {
                pages.put(page.key, page);
                requestedAt.remove(page.key);
            }
        }
        long[] saved = store.diskIndex();
        if (saved != savedRegionsSource) {
            savedRegionsSource = saved;
            for (long key : saved) {
                savedRegions.add(key);
            }
        }
    }
}
