package dev.lightmap.core;

/**
 * Plain counters for checking what the mod actually did.
 *
 * <p>Each field is written by one thread only (named in its prefix) and read without
 * synchronisation by the stats log, so a reading can be a few events behind. That is fine for
 * what they are for: confirming that a still frame does no work, and sizing the per-tick budgets.
 */
public final class Stats {
    // Client thread.
    public long clientChunksCaptured;
    public long clientSectionsCopied;
    public long clientBlockEvents;
    public long clientBlockEventsKept;
    public long clientUploadCalls;
    public long clientUploadBytes;
    public long clientTexturesCreated;
    public long clientTexturesClosed;
    public long clientCaptureNanos;
    public long clientUploadNanos;

    // Map worker.
    public long workerChunksApplied;
    public long workerChunksUnchanged;
    public long workerTilesMarked;
    public long workerPagesCreated;
    public long workerPagesLoaded;
    public long workerPagesSaved;
    public long workerPagesEvicted;
    public long workerBytesSaved;
    public long workerApplyNanos;

    public String summary() {
        return "capture: chunks=" + clientChunksCaptured
                + " sections=" + clientSectionsCopied
                + " ms=" + ms(clientCaptureNanos)
                + " | block events: seen=" + clientBlockEvents
                + " kept=" + clientBlockEventsKept
                + " | worker: applied=" + workerChunksApplied
                + " unchanged=" + workerChunksUnchanged
                + " tiles=" + workerTilesMarked
                + " ms=" + ms(workerApplyNanos)
                + " | upload: calls=" + clientUploadCalls
                + " kB=" + clientUploadBytes / 1024
                + " ms=" + ms(clientUploadNanos)
                + " | pages: new=" + workerPagesCreated
                + " loaded=" + workerPagesLoaded
                + " saved=" + workerPagesSaved
                + " (" + workerBytesSaved / 1024 + " kB)"
                + " evicted=" + workerPagesEvicted
                + " | textures: +" + clientTexturesCreated
                + " -" + clientTexturesClosed;
    }

    private static String ms(long nanos) {
        return String.valueOf(Math.round(nanos / 10_000.0) / 100.0);
    }
}
