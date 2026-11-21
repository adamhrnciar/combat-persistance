package com.pottersgame;

import com.google.gson.JsonParser;
import com.pottersgame.api.CombatNpcEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The KEEP_ON_NPC_DEATH split and the durability of kept items. */
class KeepOnNpcDeathTest {
    @TempDir static Path bootstrapDirectory;
    @TempDir Path directory;
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String NPC = "00000000-0000-0000-0000-000000000003";

    @BeforeAll static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        java.util.stream.Stream.of(Items.DIAMOND, Items.DIAMOND_BOOTS, Items.SHIELD, Items.APPLE).forEach(item -> {
            var holder = item.builtInRegistryHolder();
            if (!holder.areComponentsBound()) holder.bindComponents(net.minecraft.core.component.DataComponentMap.EMPTY);
        });
        // Tracker writes log through Combatpersistence; initialize its static
        // managers against an isolated directory, never a real data store.
        Object loader = FabricLoader.getInstance();
        Field configDirectory = loader.getClass().getDeclaredField("configDir");
        configDirectory.setAccessible(true);
        Object previousDirectory = configDirectory.get(loader);
        try {
            configDirectory.set(loader, bootstrapDirectory);
            Class.forName(Combatpersistence.class.getName(), true, Combatpersistence.class.getClassLoader());
        } finally {
            configDirectory.set(loader, previousDirectory);
        }
    }

    // ---------------------------------------------------------------- split

    @Test void withoutListenersEveryItemDrops() {
        var split = CombatTracker.splitForKeep(OWNER, encoded(), decoded(), CombatNpcEvents.KEEP_ON_NPC_DEATH.invoker());
        assertTrue(split.kept().isEmpty());
        assertEquals(4, split.dropped().size());
    }

    @Test void keptItemsReuseTheirStoredEncodingAndSlot() {
        List<SlottedInventory.SlotItem> encoded = encoded();
        var split = CombatTracker.splitForKeep(OWNER, encoded, decoded(),
            (owner, stack, slot, equipment) -> equipment);
        assertEquals(List.of(encoded.get(1), encoded.get(2)), split.kept());
        assertEquals(List.of(36, 40), split.kept().stream().map(item -> item.slot).toList());
        assertEquals(2, split.dropped().size());
        assertTrue(split.dropped().stream().anyMatch(item -> item.slot() == 0 && item.stack().is(Items.DIAMOND)));
        assertTrue(split.dropped().stream().anyMatch(item -> item.slot() == SlottedInventory.NO_SLOT
            && item.stack().is(Items.APPLE)));
    }

    @Test void listenerReceivesOwnerSlotAndEquipmentFlag() {
        List<String> seen = new java.util.ArrayList<>();
        CombatTracker.splitForKeep(OWNER, encoded(), decoded(), (owner, stack, slot, equipment) -> {
            assertEquals(OWNER, owner);
            seen.add(slot + ":" + equipment);
            return false;
        });
        assertEquals(List.of("0:false", "36:true", "40:true", "-1:false"), seen);
    }

    @Test void listenerCannotChangeTheDroppedStack() {
        var split = CombatTracker.splitForKeep(OWNER, encoded(), decoded(), (owner, stack, slot, equipment) -> {
            stack.setCount(0);
            return false;
        });
        assertEquals(2, split.dropped().get(0).stack().getCount());
    }

    @Test void throwingListenerDropsTheItemAsUsual() {
        var split = CombatTracker.splitForKeep(OWNER, encoded(), decoded(), (owner, stack, slot, equipment) -> {
            if (slot == 36) throw new IllegalStateException("listener bug");
            return slot == 40;
        });
        assertEquals(List.of(40), split.kept().stream().map(item -> item.slot).toList());
        assertEquals(3, split.dropped().size());
    }

    @Test void incompleteDecodeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> CombatTracker.splitForKeep(OWNER, encoded(),
            decoded().subList(0, 3), (owner, stack, slot, equipment) -> true));
    }

    // ---------------------------------------------------------------- persistence

    @Test void keptItemsArePersistedWithTheDeathTombstone() throws Exception {
        Files.writeString(target(), state());
        CombatTracker tracker = new CombatTracker(directory);
        tracker.markOfflineDeath(OWNER, List.of(new SlottedInventory.SlotItem(36, "boots")));

        // The file written by that single synchronous save holds both.
        var root = JsonParser.parseString(Files.readString(target())).getAsJsonObject();
        assertTrue(root.getAsJsonArray("offlineDeaths").toString().contains(OWNER.toString()));
        assertTrue(root.getAsJsonObject("keptItems").has(OWNER.toString()));
        assertTrue(root.getAsJsonObject("activeNPCs").isEmpty());

        CombatTracker reloaded = new CombatTracker(directory);
        assertTrue(reloaded.isPendingDeath(OWNER));
        assertNull(reloaded.getNPCForPlayer(OWNER));
        var batches = reloaded.getKeptBatches(OWNER);
        assertEquals(1, batches.size());
        assertNotNull(batches.get(0).batchId);
        assertEquals(36, batches.get(0).items.get(0).slot);
        assertEquals("boots", batches.get(0).items.get(0).nbt);
    }

    @Test void nothingKeptLeavesNoBatch() throws Exception {
        Files.writeString(target(), state());
        new CombatTracker(directory).markOfflineDeath(OWNER);
        CombatTracker reloaded = new CombatTracker(directory);
        assertTrue(reloaded.isPendingDeath(OWNER));
        assertTrue(reloaded.getKeptBatches(OWNER).isEmpty());
        assertFalse(JsonParser.parseString(Files.readString(target())).getAsJsonObject()
            .getAsJsonObject("keptItems").has(OWNER.toString()));
    }

    @Test void failedTombstoneWriteKeepsTheWholeInventoryWithTheNpc() throws Exception {
        Files.writeString(target(), state());
        CombatTracker tracker = new CombatTracker(directory);
        blockWrites();
        assertThrows(RuntimeException.class,
            () -> tracker.markOfflineDeath(OWNER, List.of(new SlottedInventory.SlotItem(36, "boots"))));
        assertEquals(UUID.fromString(NPC), tracker.getNPCForPlayer(OWNER));
        assertFalse(tracker.isPendingDeath(OWNER));
        assertTrue(tracker.getKeptBatches(OWNER).isEmpty());
    }

    @Test void secondDeathAddsASeparateBatch() throws Exception {
        CombatTracker tracker = new CombatTracker(directory);
        tracker.markOfflineDeath(OWNER, List.of(new SlottedInventory.SlotItem(36, "boots")));
        tracker.markOfflineDeath(OWNER, List.of(new SlottedInventory.SlotItem(0, "sword")));
        var batches = new CombatTracker(directory).getKeptBatches(OWNER);
        assertEquals(2, batches.size());
        assertNotEquals(batches.get(0).batchId, batches.get(1).batchId);
    }

    @Test void consumingABatchIsDurable() throws Exception {
        CombatTracker tracker = new CombatTracker(directory);
        tracker.markOfflineDeath(OWNER, List.of(new SlottedInventory.SlotItem(36, "boots")));
        UUID batch = tracker.getKeptBatches(OWNER).get(0).batchId;
        tracker.removeKeptBatch(OWNER, batch);
        assertTrue(tracker.getKeptBatches(OWNER).isEmpty());
        assertTrue(new CombatTracker(directory).getKeptBatches(OWNER).isEmpty());
    }

    @Test void failedBatchConsumptionRetainsTheBatch() throws Exception {
        CombatTracker tracker = new CombatTracker(directory);
        tracker.markOfflineDeath(OWNER, List.of(new SlottedInventory.SlotItem(36, "boots")));
        UUID batch = tracker.getKeptBatches(OWNER).get(0).batchId;
        blockWrites();
        assertThrows(RuntimeException.class, () -> tracker.removeKeptBatch(OWNER, batch));
        assertEquals(batch, tracker.getKeptBatches(OWNER).get(0).batchId);
    }

    @Test void malformedKeptItemsRefuseStartupWithoutChangingFile() throws Exception {
        String batch = "{\"batchId\":\"00000000-0000-0000-0000-0000000000aa\",\"items\":[{\"slot\":36,\"nbt\":\"x\"}]}";
        for (String kept : new String[] {"null", "{\"" + OWNER + "\":null}", "{\"" + OWNER + "\":[]}",
                "{\"" + OWNER + "\":[null]}", "{\"" + OWNER + "\":[{\"items\":[{\"nbt\":\"x\"}]}]}",
                "{\"" + OWNER + "\":[{\"batchId\":\"00000000-0000-0000-0000-0000000000aa\",\"items\":[]}]}",
                "{\"" + OWNER + "\":[{\"batchId\":\"00000000-0000-0000-0000-0000000000aa\",\"items\":[{\"slot\":1}]}]}",
                "{\"" + OWNER + "\":[" + batch + "," + batch + "]}"}) {
            String json = "{\"offlineDeaths\":[],\"activeNPCs\":{},\"keptItems\":" + kept + "}";
            Files.writeString(target(), json);
            assertThrows(IllegalStateException.class, () -> new CombatTracker(directory), kept);
            assertEquals(json, Files.readString(target()));
        }
    }

    @Test void dataWithoutKeptItemsStillLoads() throws Exception {
        Files.writeString(target(), state());
        assertTrue(new CombatTracker(directory).getKeptBatches(OWNER).isEmpty());
    }

    // ---------------------------------------------------------------- helpers

    private static List<SlottedInventory.SlotItem> encoded() {
        return List.of(new SlottedInventory.SlotItem(0, "diamond"), new SlottedInventory.SlotItem(36, "boots"),
            new SlottedInventory.SlotItem(40, "shield"), new SlottedInventory.SlotItem(SlottedInventory.NO_SLOT, "apple"));
    }

    private static List<SlottedInventory.SlottedStack> decoded() {
        return List.of(new SlottedInventory.SlottedStack(0, new ItemStack(Items.DIAMOND, 2)),
            new SlottedInventory.SlottedStack(36, new ItemStack(Items.DIAMOND_BOOTS)),
            new SlottedInventory.SlottedStack(40, new ItemStack(Items.SHIELD)),
            new SlottedInventory.SlottedStack(SlottedInventory.NO_SLOT, new ItemStack(Items.APPLE, 3)));
    }

    /** Replace the data file with a directory so the atomic rename fails. */
    private void blockWrites() throws Exception {
        Files.deleteIfExists(target());
        Files.createDirectory(target());
        Files.writeString(target().resolve("prevent-replacement"), "keep");
    }

    private Path target() { return directory.resolve("combat_persistence_data.json"); }

    private static String state() {
        return "{\"offlineDeaths\":[],\"activeNPCs\":{\"" + OWNER + "\":{\"playerUuid\":\"" + OWNER
            + "\",\"npcUuid\":\"" + NPC + "\",\"items\":[{\"slot\":36,\"nbt\":\"boots\"},{\"slot\":0,\"nbt\":\"diamond\"}]}}}";
    }
}
