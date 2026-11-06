package com.pottersgame;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class DiskIOTest {
    @TempDir Path directory;

    @Test void failedSerializationKeepsPreviousSnapshot() throws Exception {
        Path target = directory.resolve("inventory.json");
        Files.writeString(target, "previous authoritative snapshot");
        Gson broken = new GsonBuilder().registerTypeAdapter(String.class,
            (JsonSerializer<String>) (value, type, context) -> { throw new IllegalStateException("encode failure"); }).create();
        assertThrows(IllegalStateException.class, () -> DiskIO.writeJson(target.toFile(), broken, "new"));
        assertEquals("previous authoritative snapshot", Files.readString(target));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    @Test void synchronousWriteCannotBeOverwrittenByQueuedOlderWrite() throws Exception {
        Path target = directory.resolve("inventory.json");
        DiskIO.async(() -> DiskIO.writeJson(target.toFile(), new Gson(), Map.of("generation", 1)));
        DiskIO.sync(() -> DiskIO.writeJson(target.toFile(), new Gson(), Map.of("generation", 2)));
        assertEquals("{\"generation\":2}", Files.readString(target));
    }

    @Test void failedWritePropagatesAndKeepsPreviousDestination() throws Exception {
        Path target = directory.resolve("existing-directory");
        Files.createDirectory(target);
        Files.writeString(target.resolve("keep"), "retained");
        assertThrows(CompletionException.class, () -> DiskIO.sync(() ->
            DiskIO.writeJson(target.toFile(), new Gson(), Map.of("inventory", "new"))));
        assertEquals("retained", Files.readString(target.resolve("keep")));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    @Test void dataFilesArePrivateOnPosix() throws Exception {
        Path target = directory.resolve("auth.json");
        DiskIO.writeJson(target.toFile(), new Gson(), Map.of("passwordHash", "test-only"));
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(target));
        }
        DiskIO.writeJson(target.toFile(), new Gson(), Map.of("generation", 2));
        assertEquals("{\"generation\":2}", Files.readString(target));
    }
}
