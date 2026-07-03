package com.pottersgame;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Single writer thread for all config/data file saves. Serializing every write
 * through one queue guarantees snapshots reach the disk in the order they were
 * taken — with a shared pool, two concurrent saves of the same file could race
 * and leave the OLDER snapshot as the file's final contents.
 */
public final class DiskIO {
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "combat-persistence-io");
        t.setDaemon(true);
        return t;
    });

    private DiskIO() {}

    /** Queue a write without blocking the server thread. */
    public static void async(Runnable write) {
        CompletableFuture.runAsync(write, WRITER);
    }

    /**
     * Queue a write and wait for it to hit the disk. Because it goes through the
     * same queue, every previously queued write is flushed first — a sync save
     * can never be overwritten afterwards by a stale async snapshot.
     */
    public static void sync(Runnable write) {
        CompletableFuture.runAsync(write, WRITER).join();
    }
}
