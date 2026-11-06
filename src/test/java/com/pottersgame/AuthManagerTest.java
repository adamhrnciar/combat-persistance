package com.pottersgame;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AuthManagerTest {
    @TempDir Path directory;

    @Test void passwordLimitCountsUtf8Bytes() {
        assertTrue(AuthManager.isPasswordWithinLimit("a".repeat(64), 64));
        assertFalse(AuthManager.isPasswordWithinLimit("a".repeat(65), 64));
        assertTrue(AuthManager.isPasswordWithinLimit("é".repeat(36), 64));
        assertFalse(AuthManager.isPasswordWithinLimit("é".repeat(37), 64));
        assertTrue(AuthManager.isPasswordWithinLimit("界".repeat(24), 64));
        assertFalse(AuthManager.isPasswordWithinLimit("界".repeat(25), 64));
    }

    @Test void corruptAccountStoreRefusesStartupAndPreservesFile() throws Exception {
        Path target = directory.resolve("auth_data.json");
        Files.writeString(target, "{ broken json");
        assertThrows(IllegalStateException.class, () -> new AuthManager(directory));
        assertEquals("{ broken json", Files.readString(target));
    }

    @Test void nullAccountStoreRefusesStartup() throws Exception {
        Files.writeString(directory.resolve("auth_data.json"), "null");
        assertThrows(IllegalStateException.class, () -> new AuthManager(directory));
    }

    @Test void missingPasswordHashRefusesStartup() throws Exception {
        Files.writeString(directory.resolve("auth_data.json"),
            "{\"00000000-0000-0000-0000-000000000001\":{\"username\":\"victim\"}}");
        assertThrows(IllegalStateException.class, () -> new AuthManager(directory));
    }

    @Test void pendingLocationWithoutDimensionRefusesStartup() throws Exception {
        Files.writeString(directory.resolve("pending_locations.json"),
            "{\"00000000-0000-0000-0000-000000000001\":{\"x\":12,\"y\":64,\"z\":12}}");
        assertThrows(IllegalStateException.class, () -> new AuthManager(directory));
    }

    @Test void unknownInventoryFormatRefusesStartupAndPreservesFile() throws Exception {
        Path target = directory.resolve("pending_inventories.json");
        String contents = "{\"formatVersion\":3,\"inventories\":{}}";
        Files.writeString(target, contents);
        assertThrows(IllegalStateException.class, () -> new AuthManager(directory));
        assertEquals(contents, Files.readString(target));
    }

    @Test void inventoryEntryWithoutItemRefusesStartup() throws Exception {
        Files.writeString(directory.resolve("pending_inventories.json"),
            "{\"formatVersion\":2,\"inventories\":{\"00000000-0000-0000-0000-000000000001\":[{\"slot\":0}]}}");
        assertThrows(IllegalStateException.class, () -> new AuthManager(directory));
    }
}
