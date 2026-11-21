package com.pottersgame;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Restoring kept items (idempotence, undo, fit) and the team rules. */
class KeptRestoreTest {
    @TempDir static Path bootstrapDirectory;
    @TempDir Path directory;
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private CombatTracker tracker;
    private ServerPlayer player;
    private Inventory inventory;
    private final Set<String> tags = new HashSet<>();
    private int tagLimit = 1024;

    @BeforeAll static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        java.util.stream.Stream.of(Items.DIAMOND, Items.DIAMOND_BOOTS, Items.DIRT).forEach(item -> {
            var holder = item.builtInRegistryHolder();
            if (!holder.areComponentsBound()) holder.bindComponents(net.minecraft.core.component.DataComponentMap.EMPTY);
        });
        Object loader = FabricLoader.getInstance();
        Field configDirectory = loader.getClass().getDeclaredField("configDir");
        configDirectory.setAccessible(true);
        Object previousDirectory = configDirectory.get(loader);
        try {
            configDirectory.set(loader, bootstrapDirectory);
            Class.forName(Combatpersistence.class.getName(), true, Combatpersistence.class.getClassLoader());
            if (Combatpersistence.config == null) Combatpersistence.config = new CombatConfig();
        } finally {
            configDirectory.set(loader, previousDirectory);
        }
    }

    /** Async saves run on DiskIO's single writer thread. Drain it before JUnit
     *  deletes the temp directory, or a late write races the cleanup. */
    @AfterEach void drainAsyncWrites() {
        DiskIO.sync(() -> {});
    }

    @BeforeEach void setUp() {
        tracker = new CombatTracker(directory);
        player = mock(ServerPlayer.class);
        inventory = new Inventory(player, new EntityEquipment());
        when(player.getInventory()).thenReturn(inventory);
        when(player.getUUID()).thenReturn(OWNER);
        when(player.getName()).thenReturn(Component.literal("Owner"));
        when(player.entityTags()).thenReturn(tags);
        when(player.addTag(anyString())).thenAnswer(call -> tags.size() < tagLimit && tags.add(call.getArgument(0)));
        when(player.removeTag(anyString())).thenAnswer(call -> tags.remove(call.<String>getArgument(0)));
    }

    // ---------------------------------------------------------------- restore

    @Test void wholeBatchIsSavedWithItsMarkerThenConsumed() {
        CombatTracker.KeptBatch batch = pendingBatch();
        AtomicInteger saves = new AtomicInteger();
        var result = tracker.restoreKeptBatch(player, batch, KeptRestoreTest::decoded, saved -> {
            saves.incrementAndGet();
            assertTrue(tags.contains(marker(batch)), "player data must carry the marker");
            assertTrue(inventory.getItem(36).is(Items.DIAMOND_BOOTS));
            assertTrue(inventory.getItem(0).is(Items.DIAMOND));
        });
        assertEquals(CombatTracker.KeptOutcome.RESTORED, result.outcome());
        assertEquals(1, saves.get());
        assertTrue(tags.isEmpty());
        assertTrue(new CombatTracker(directory).getKeptBatches(OWNER).isEmpty());
        assertEquals(1, count(Items.DIAMOND_BOOTS));
        assertEquals(2, count(Items.DIAMOND));
    }

    @Test void presentMarkerSkipsPlacementAndOnlyConsumes() {
        CombatTracker.KeptBatch batch = pendingBatch();
        tags.add(marker(batch));
        var result = tracker.restoreKeptBatch(player, batch,
            () -> { throw new AssertionError("must not decode an already placed batch"); },
            saved -> fail("must not save again"));
        assertEquals(CombatTracker.KeptOutcome.ALREADY_PLACED, result.outcome());
        assertTrue(inventory.isEmpty());
        assertTrue(tags.isEmpty());
        assertTrue(tracker.getKeptBatches(OWNER).isEmpty());
    }

    @Test void saveFailureUndoesPlacementAndKeepsBatchPending() {
        CombatTracker.KeptBatch batch = pendingBatch();
        inventory.setItem(5, new ItemStack(Items.DIRT, 3));
        var result = tracker.restoreKeptBatch(player, batch, KeptRestoreTest::decoded,
            saved -> { throw new IllegalStateException("disk full"); });
        assertEquals(CombatTracker.KeptOutcome.REFUSED, result.outcome());
        assertEquals(0, count(Items.DIAMOND_BOOTS));
        assertEquals(0, count(Items.DIAMOND));
        assertEquals(3, inventory.getItem(5).getCount(), "unrelated items stay");
        assertTrue(tags.isEmpty());
        assertEquals(1, new CombatTracker(directory).getKeptBatches(OWNER).size());

        // A later retry places the batch exactly once.
        assertEquals(CombatTracker.KeptOutcome.RESTORED,
            tracker.restoreKeptBatch(player, batch, KeptRestoreTest::decoded, saved -> {}).outcome());
        assertEquals(1, count(Items.DIAMOND_BOOTS));
        assertEquals(2, count(Items.DIAMOND));
    }

    @Test void consumeFailureKeepsMarkerSoRetriesNeverPlaceTwice() throws Exception {
        CombatTracker.KeptBatch batch = pendingBatch();
        blockWrites();
        tracker.restoreKeptBatch(player, batch, KeptRestoreTest::decoded, saved -> {});
        assertTrue(tags.contains(marker(batch)));
        assertEquals(1, tracker.getKeptBatches(OWNER).size());

        var retry = tracker.restoreKeptBatch(player, batch, KeptRestoreTest::decoded, saved -> fail("no second save"));
        assertEquals(CombatTracker.KeptOutcome.ALREADY_PLACED, retry.outcome());
        assertEquals(1, count(Items.DIAMOND_BOOTS));
        assertEquals(2, count(Items.DIAMOND));

        unblockWrites();
        tracker.restoreKeptBatch(player, batch, KeptRestoreTest::decoded, saved -> fail("no second save"));
        assertTrue(tracker.getKeptBatches(OWNER).isEmpty());
        assertTrue(tags.isEmpty());
        assertEquals(1, count(Items.DIAMOND_BOOTS));
    }

    @Test void refusesWhenTheMarkerCannotBeAdded() {
        CombatTracker.KeptBatch batch = pendingBatch();
        tagLimit = 0;
        var result = tracker.restoreKeptBatch(player, batch, KeptRestoreTest::decoded, saved -> fail("must not save"));
        assertEquals(CombatTracker.KeptOutcome.REFUSED, result.outcome());
        assertTrue(inventory.isEmpty());
        assertEquals(1, tracker.getKeptBatches(OWNER).size());
    }

    @Test void batchThatDoesNotFitStaysPendingUntilThereIsRoom() {
        CombatTracker.KeptBatch batch = pendingBatch();
        for (int slot = 0; slot < 36; slot++) inventory.setItem(slot, new ItemStack(Items.DIRT));
        var result = tracker.restoreKeptBatch(player, batch, KeptRestoreTest::decoded, saved -> fail("must not save"));
        assertEquals(CombatTracker.KeptOutcome.NO_ROOM, result.outcome());
        assertEquals(1, result.missingSlots(), "boots fit their armor slot, the diamonds need one free slot");
        assertTrue(inventory.getItem(36).isEmpty(), "nothing is placed partially");
        assertTrue(tags.isEmpty());
        assertEquals(1, new CombatTracker(directory).getKeptBatches(OWNER).size());

        inventory.setItem(7, ItemStack.EMPTY);
        assertEquals(CombatTracker.KeptOutcome.RESTORED,
            tracker.restoreKeptBatch(player, batch, KeptRestoreTest::decoded, saved -> {}).outcome());
        assertTrue(inventory.getItem(7).is(Items.DIAMOND));
        assertTrue(inventory.getItem(36).is(Items.DIAMOND_BOOTS));
    }

    @Test void looseItemsNeverTakeAnotherItemsOriginalSlot() {
        inventory.setItem(0, new ItemStack(Items.DIRT));
        var plan = CombatTracker.planKeptPlacement(inventory, List.of(
            new SlottedInventory.SlottedStack(0, new ItemStack(Items.DIAMOND)),
            new SlottedInventory.SlottedStack(1, new ItemStack(Items.DIAMOND_BOOTS))));
        assertEquals(0, plan.missingSlots());
        assertArrayEquals(new int[] {2, 1}, plan.slots());
    }

    @Test void staleMarkersAreRemovedButPendingOnesStay() {
        CombatTracker.KeptBatch batch = pendingBatch();
        tags.add(marker(batch));
        tags.add(CombatTracker.KEPT_MARKER_PREFIX + UUID.randomUUID());
        tags.add("some_other_tag");
        CombatTracker.removeStaleKeptMarkers(player, tracker.getKeptBatches(OWNER));
        assertEquals(Set.of(marker(batch), "some_other_tag"), tags);
    }

    // ---------------------------------------------------------------- teams

    @Test void teammateProtectionUsesOwnersCurrentTeamAndPets() {
        Scoreboard scoreboard = new Scoreboard();
        PlayerTeam red = scoreboard.addPlayerTeam("red");
        PlayerTeam blue = scoreboard.addPlayerTeam("blue");
        red.setAllowFriendlyFire(false);
        blue.setAllowFriendlyFire(false);
        scoreboard.addPlayerToTeam("Owner", red);
        net.minecraft.world.level.Level level = mock(net.minecraft.world.level.Level.class);
        when(level.getScoreboard()).thenReturn(scoreboard);
        CombatNPC npc = mock(CombatNPC.class);
        npc.originalPlayerName = "Owner";
        when(npc.level()).thenReturn(level);
        when(npc.getTeam()).thenReturn(red);

        ServerPlayer mate = playerOn(red);
        ServerPlayer enemy = playerOn(blue);
        assertTrue(CombatEvents.isProtectedTeammate(mate, npc));
        assertFalse(CombatEvents.isProtectedTeammate(enemy, npc));
        assertFalse(CombatEvents.isProtectedTeammate(null, npc));
        assertFalse(CombatEvents.isProtectedTeammate(mock(net.minecraft.world.entity.LivingEntity.class), npc));

        TamableAnimal matesPet = mock(TamableAnimal.class);
        when(matesPet.getOwner()).thenReturn(mate);
        assertTrue(CombatEvents.isProtectedTeammate(matesPet, npc));
        TamableAnimal strayPet = mock(TamableAnimal.class);
        assertFalse(CombatEvents.isProtectedTeammate(strayPet, npc));

        // The owner moved to blue while offline: their current team decides.
        scoreboard.addPlayerToTeam("Owner", blue);
        assertFalse(CombatEvents.isProtectedTeammate(mate, npc));
        assertTrue(CombatEvents.isProtectedTeammate(enemy, npc));

        // No team by name: fall back to the NPC's own entry.
        scoreboard.removePlayerFromTeam("Owner", blue);
        assertTrue(CombatEvents.isProtectedTeammate(mate, npc));

        red.setAllowFriendlyFire(true);
        assertFalse(CombatEvents.isProtectedTeammate(mate, npc));
    }

    @Test void startupSweepRemovesOnlyRecordedStaleNpcEntries() {
        Scoreboard scoreboard = new Scoreboard();
        PlayerTeam red = scoreboard.addPlayerTeam("red");
        UUID staleNpc = UUID.randomUUID();
        UUID loadedNpc = UUID.randomUUID();
        UUID operatorsMob = UUID.randomUUID();
        UUID playerNamedLikeUuid = UUID.randomUUID();
        for (String entry : List.of("Owner", staleNpc.toString(), loadedNpc.toString(), operatorsMob.toString(),
                playerNamedLikeUuid.toString())) {
            scoreboard.addPlayerToTeam(entry, red);
        }
        tracker.recordNpcTeamEntry(staleNpc);
        tracker.recordNpcTeamEntry(loadedNpc);

        CombatTracker restarted = new CombatTracker(directory);
        assertEquals(Set.of(staleNpc, loadedNpc), restarted.getNpcTeamEntries(), "recorded entries survive a restart");
        assertEquals(1, restarted.sweepNpcTeamEntries(scoreboard, loadedNpc::equals));
        assertEquals(Set.of("Owner", loadedNpc.toString(), operatorsMob.toString(), playerNamedLikeUuid.toString()),
            Set.copyOf(red.getPlayers()));
        assertEquals(Set.of(loadedNpc), new CombatTracker(directory).getNpcTeamEntries());
    }

    @Test void releasingAnNpcRemovesItsEntryWhetherOrNotItIsLoaded() {
        Scoreboard scoreboard = new Scoreboard();
        PlayerTeam red = scoreboard.addPlayerTeam("red");
        UUID npc = UUID.randomUUID();
        tracker.recordNpcTeamEntry(npc);
        scoreboard.addPlayerToTeam(npc.toString(), red);
        tracker.releaseNpcTeam(scoreboard, npc);
        assertTrue(red.getPlayers().isEmpty());
        assertTrue(tracker.getNpcTeamEntries().isEmpty());
    }

    @Test void malformedNpcTeamEntriesRefuseStartup() throws Exception {
        for (String entries : new String[] {"null", "[null]"}) {
            String json = "{\"offlineDeaths\":[],\"activeNPCs\":{},\"npcTeamEntries\":" + entries + "}";
            Files.writeString(target(), json);
            assertThrows(IllegalStateException.class, () -> new CombatTracker(directory), entries);
            assertEquals(json, Files.readString(target()));
        }
    }

    // ---------------------------------------------------------------- helpers

    private CombatTracker.KeptBatch pendingBatch() {
        tracker.markOfflineDeath(OWNER, List.of(new SlottedInventory.SlotItem(36, "boots"),
            new SlottedInventory.SlotItem(0, "diamond")));
        return tracker.getKeptBatches(OWNER).get(0);
    }

    private static List<SlottedInventory.SlottedStack> decoded() {
        return List.of(new SlottedInventory.SlottedStack(36, new ItemStack(Items.DIAMOND_BOOTS)),
            new SlottedInventory.SlottedStack(0, new ItemStack(Items.DIAMOND, 2)));
    }

    private static String marker(CombatTracker.KeptBatch batch) {
        return CombatTracker.KEPT_MARKER_PREFIX + batch.batchId;
    }

    private static ServerPlayer playerOn(PlayerTeam team) {
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getTeam()).thenReturn(team);
        return player;
    }

    private int count(net.minecraft.world.item.Item item) {
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).is(item)) total += inventory.getItem(slot).getCount();
        }
        return total;
    }

    private void blockWrites() throws Exception {
        Files.deleteIfExists(target());
        Files.createDirectory(target());
        Files.writeString(target().resolve("prevent-replacement"), "keep");
    }

    private void unblockWrites() throws Exception {
        Files.delete(target().resolve("prevent-replacement"));
        Files.delete(target());
    }

    private Path target() { return directory.resolve("combat_persistence_data.json"); }
}
