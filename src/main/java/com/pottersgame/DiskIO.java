package com.pottersgame;

import com.google.gson.Gson;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
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
        CompletableFuture.runAsync(write, WRITER).exceptionally(error -> {
            Combatpersistence.LOGGER.error("Failed to persist queued combat/auth data", error);
            return null;
        });
    }

    /**
     * Queue a write and wait for it to hit the disk. Because it goes through the
     * same queue, every previously queued write is flushed first — a sync save
     * can never be overwritten afterwards by a stale async snapshot.
     */
    public static void sync(Runnable write) {
        CompletableFuture.runAsync(write, WRITER).join();
    }

    /**
     * Atomically replace a JSON file, leaving its previous contents intact if
     * serialization or writing fails. Critical callers must let failures escape
     * so they never destroy a live inventory after a failed backup.
     */
    public static void writeJson(File target, Gson gson, Object snapshot) {
        byte[] bytes = gson.toJson(snapshot).getBytes(StandardCharsets.UTF_8);
        writeBytes(target.toPath(), bytes);
    }

    /** Durable atomic write shared by JSON snapshots and vanilla player data. */
    public static void writeBytes(Path target, byte[] bytes) {
        Path destination = target.toAbsolutePath();
        Path temporary = null;
        try {
            Path directory = destination.getParent();
            Files.createDirectories(directory);
            if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
                temporary = Files.createTempFile(directory, destination.getFileName() + ".", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } else {
                temporary = Files.createTempFile(directory, destination.getFileName() + ".", ".tmp");
            }
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
            // Flush the rename as well as the file on filesystems supporting
            // directory channels. Windows does not permit opening directories.
            if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
                try (FileChannel directoryChannel = FileChannel.open(directory, StandardOpenOption.READ)) {
                    directoryChannel.force(true);
                }
            }
        } catch (IOException error) {
            throw new UncheckedIOException("Failed to atomically save " + destination.getFileName(), error);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException error) {
                    Combatpersistence.LOGGER.warn("Could not remove unfinished data file {}", temporary.getFileName(), error);
                }
            }
        }
    }
}
