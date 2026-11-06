package com.pottersgame;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CombatTrackerTest {
    @TempDir Path directory;
    private static final String OWNER = "00000000-0000-0000-0000-000000000001";
    private static final String OTHER = "00000000-0000-0000-0000-000000000002";
    private static final String NPC = "00000000-0000-0000-0000-000000000003";

    @Test void missingRecoveryFileStartsEmpty() {
        assertNull(new CombatTracker(directory).getNPCForPlayer(UUID.fromString(OWNER)));
    }

    @Test void malformedRecoveryCollectionsRefuseStartupWithoutChangingFile() throws Exception {
        for (String json : new String[] {"null", "{}", "{\"offlineDeaths\":[],\"activeNPCs\":null}",
                "{\"offlineDeaths\":null,\"activeNPCs\":{}}",
                "{\"offlineDeaths\":[],\"pendingJoinDeaths\":null,\"activeNPCs\":{}}"}) {
            rejectUnchanged(json);
        }
    }

    @Test void missingInventoryOrInvalidItemRefusesStartup() throws Exception {
        for (String inventory : new String[] {"", ",\"items\":null", ",\"items\":[null]", ",\"items\":[{}]"}) {
            rejectUnchanged(state(entry(OWNER, inventory), "[]"));
        }
    }

    @Test void duplicateNpcOrConflictingDeathRefusesStartup() throws Exception {
        rejectUnchanged(state(entry(OWNER, ",\"items\":[]") + "," + entry(OTHER, ",\"items\":[]"), "[]"));
        rejectUnchanged(state(entry(OWNER, ",\"items\":[]"), "[\"" + OWNER + "\"]"));
    }

    @Test void legacyInventoryAndOfflineDeathLoadWithoutDroppingState() throws Exception {
        Files.writeString(target(), state(entry(OWNER, ",\"inventoryNbt\":[]"), "[\"" + OTHER + "\"]"));
        CombatTracker tracker = new CombatTracker(directory);
        assertEquals(UUID.fromString(NPC), tracker.getNPCForPlayer(UUID.fromString(OWNER)));
        assertTrue(tracker.isPendingDeath(UUID.fromString(OTHER)));
    }

    @Test void failedSnapshotRemovalRetainsInMemoryOwnership() throws Exception {
        Files.writeString(target(), state(entry(OWNER, ",\"items\":[]"), "[]"));
        CombatTracker tracker = new CombatTracker(directory);
        Files.delete(target());
        Files.createDirectory(target());
        Files.writeString(target().resolve("prevent-replacement"), "keep");
        assertThrows(RuntimeException.class, () -> tracker.removeNPC(UUID.fromString(OWNER)));
        assertEquals(UUID.fromString(NPC), tracker.getNPCForPlayer(UUID.fromString(OWNER)));
    }

    private void rejectUnchanged(String json) throws Exception {
        Files.writeString(target(), json);
        assertThrows(IllegalStateException.class, () -> new CombatTracker(directory));
        assertEquals(json, Files.readString(target()));
    }

    private Path target() { return directory.resolve("combat_persistence_data.json"); }

    private static String entry(String owner, String inventory) {
        return "\"" + owner + "\":{\"playerUuid\":\"" + owner + "\",\"npcUuid\":\"" + NPC + "\"" + inventory + "}";
    }

    private static String state(String entries, String deaths) {
        return "{\"offlineDeaths\":" + deaths + ",\"activeNPCs\":{" + entries + "}}";
    }
}
