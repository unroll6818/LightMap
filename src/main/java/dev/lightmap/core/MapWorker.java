package dev.lightmap.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * The one background thread all map work runs on, in submission order.
 *
 * <p>A single thread is deliberate. Colouring a chunk takes tens of microseconds, so there is
 * nothing to gain from more, and one thread means the page data needs no locking and a session
 * that is closing always finishes saving before the next session of the same world starts
 * reading.
 */
public final class MapWorker {
    private static final Logger LOG = LoggerFactory.getLogger("lightmap");
    private static final long POLL_MS = 100;
    private static final long IDLE_EVERY_NS = 200_000_000L;

    private final LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
    private final Thread thread;
    private volatile BooleanSupplier idle;
    private volatile boolean running = true;

    public MapWorker(String name) {
        thread = new Thread(this::run, name);
        thread.setDaemon(true);
        thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
        thread.start();
    }

    public void submit(Runnable task) {
        queue.add(task);
    }

    /**
     * Sets the upkeep step to run between tasks. It should do one bounded piece of work and
     * return true if more is waiting.
     */
    public void setIdle(BooleanSupplier idle) {
        this.idle = idle;
    }

    /** Blocks until everything submitted so far has run, or the timeout passes. */
    public boolean await(long timeoutMs) {
        CountDownLatch done = new CountDownLatch(1);
        submit(done::countDown);
        try {
            return done.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void shutdown() {
        running = false;
        thread.interrupt();
    }

    public boolean isWorkerThread() {
        return Thread.currentThread() == thread;
    }

    private void run() {
        long lastIdle = System.nanoTime();
        while (running) {
            Runnable task;
            try {
                task = queue.poll(POLL_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                continue;
            }
            if (task != null) {
                try {
                    task.run();
                } catch (Throwable t) {
                    LOG.error("[lightmap] map task failed", t);
                }
            }
            // Upkeep runs when there is a lull, and at least a few times a second under load so
            // that saving cannot be starved by a steady stream of chunks.
            long now = System.nanoTime();
            BooleanSupplier step = idle;
            if (step != null && (task == null || queue.isEmpty() || now - lastIdle >= IDLE_EVERY_NS)) {
                lastIdle = now;
                try {
                    boolean more = step.getAsBoolean();
                    while (more && running && queue.isEmpty()) {
                        more = step.getAsBoolean();
                    }
                } catch (Throwable t) {
                    LOG.error("[lightmap] map upkeep failed", t);
                }
            }
        }
    }
}
