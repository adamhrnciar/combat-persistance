package com.pottersgame;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** The END_SERVER_TICK and ENTITY_LOAD handlers, driven with mocked server objects. */
class CombatEventsTest {
    @TempDir static Path bootstrapDirectory;
    @TempDir Path directory;
    private CombatConfig previousConfig;
    private CombatConfig config;

    private final MinecraftServer server = mock(MinecraftServer.class);
    private final ServerLevel level = mock(ServerLevel.class);
    private final PlayerList playerList = mock(PlayerList.class);
    /** Stands in for PlayerList.players, the live list getPlayers() returns. */
    private final List<ServerPlayer> livePlayers = new ArrayList<>();

    @BeforeAll static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Object loader = FabricLoader.getInstance();
        Field configDirectory = loader.getClass().getDeclaredField("configDir");
        configDirectory.setAccessible(true);
        Object previousDirectory = configDirectory.get(loader);
        try {
            configDirectory.set(loader, bootstrapDirectory);
            Class.forName(Combatpersistence.class.getName(), true, Combatpersistence.class.getClassLoader());
            Class.forName(CombatConfig.class.getName(), true, CombatConfig.class.getClassLoader());
        } finally {
            configDirectory.set(loader, previousDirectory);
        }
    }

    @BeforeEach void setUp() {
        previousConfig = Combatpersistence.config;
        config = new CombatConfig();
        config.enableAuth = true;
        config.forceAuthInOfflineMode = true;
        config.enableIpAutoLogin = false;
        Combatpersistence.config = config;
        when(level.getServer()).thenReturn(server);
        when(server.getPlayerList()).thenReturn(playerList);
        when(playerList.getPlayers()).thenReturn(livePlayers);
        // tick() looks for the NPC in every level; the mocked level never finds
        // it, like a chunk that is loaded but not tracked.
        when(server.getAllLevels()).thenReturn(List.of(level));
        when(server.getScoreboard()).thenReturn(mock(net.minecraft.server.ServerScoreboard.class));
    }

    @AfterEach void tearDown() {
        Combatpersistence.config = previousConfig;
        CombatEvents.joinTimes.clear();
    }

    @Test void loginTimeoutDisconnectsEveryTimedOutPlayerWithoutCrashing() {
        long longAgo = System.currentTimeMillis() - (config.authTimeoutSeconds + 5) * 1000L;
        List<ServerPlayer> players = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ServerPlayer player = unauthenticatedPlayer(new UUID(0, 100 + i));
            CombatEvents.joinTimes.put(player.getUUID(), longAgo);
            players.add(player);
        }
        livePlayers.addAll(players);

        // Before the fix this threw ConcurrentModificationException: the
        // disconnect removes the player from the list being iterated.
        assertDoesNotThrow(() -> CombatEvents.onEndServerTick(server, new CombatTracker(directory), config));

        for (ServerPlayer player : players) verify(player.connection).disconnect(any(Component.class));
        assertTrue(livePlayers.isEmpty(), "Every timed-out player was disconnected");
    }

    @Test void staleNpcIsDiscardedAtTickEndNotDuringLoad() {
        CombatTracker tracker = new CombatTracker(directory);
        CombatNPC npc = npc(UUID.randomUUID());

        CombatEvents.onEntityLoad(npc, tracker);
        verify(npc, never()).discard();

        CombatEvents.onEndServerTick(server, tracker, config);
        verify(npc).discard();

        // The queue is drained: a later tick does not touch it again.
        CombatEvents.onEndServerTick(server, tracker, config);
        verify(npc, times(1)).discard();
    }

    @Test void npcAlreadyRemovedBeforeTickEndIsLeftAlone() throws Exception {
        CombatTracker tracker = new CombatTracker(directory);
        CombatNPC npc = npc(UUID.randomUUID());
        CombatEvents.onEntityLoad(npc, tracker);
        // isRemoved() is final; mark it removed the way vanilla does.
        Field removalReason = net.minecraft.world.entity.Entity.class.getDeclaredField("removalReason");
        removalReason.setAccessible(true);
        removalReason.set(npc, net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK);
        assertTrue(npc.isRemoved());

        CombatEvents.onEndServerTick(server, tracker, config);
        verify(npc, never()).discard();
    }

    @Test void npcOfTaggedOwnerSurvivesLoad() {
        CombatTracker tracker = new CombatTracker(directory);
        UUID npcUuid = UUID.randomUUID();
        ServerPlayer owner = unauthenticatedPlayer(new UUID(0, 1));
        tracker.addNPC(owner.getUUID(), npcUuid, List.of(), level);
        tracker.tag(owner, 60);
        CombatNPC npc = npc(npcUuid);

        CombatEvents.onEntityLoad(npc, tracker);
        CombatEvents.onEndServerTick(server, tracker, config);
        verify(npc, never()).discard();
    }

    @Test void npcWhoseOwnerTagRanOutIsDiscardedButEntryKeptForRestore() {
        CombatTracker tracker = new CombatTracker(directory);
        UUID npcUuid = UUID.randomUUID();
        ServerPlayer owner = unauthenticatedPlayer(new UUID(0, 1));
        tracker.addNPC(owner.getUUID(), npcUuid, List.of(), level);
        tracker.tag(owner, 0); // expired: tick() would have missed an untracked NPC
        CombatNPC npc = npc(npcUuid);

        assertFalse(tracker.isNpcLive(npcUuid));
        CombatEvents.onEntityLoad(npc, tracker);
        CombatEvents.onEndServerTick(server, tracker, config);

        verify(npc).discard();
        assertEquals(npcUuid, tracker.getNPCForPlayer(owner.getUUID()),
            "The tracker entry stays so the owner gets the items back on rejoin");
        assertFalse(tracker.isPendingDeath(owner.getUUID()));
    }

    @Test void npcDyingAfterOwnerTagEndedDropsNothingAndKeepsEntry() {
        CombatTracker tracker = new CombatTracker(directory);
        UUID npcUuid = UUID.randomUUID();
        ServerPlayer owner = unauthenticatedPlayer(new UUID(0, 1));
        tracker.addNPC(owner.getUUID(), npcUuid, List.of(), level);
        tracker.tag(owner, 0);
        CombatNPC npc = npc(npcUuid);
        // In a server level, so without the guard the death would commit and drop.
        doReturn(level).when(npc).level();

        tracker.handleEntityDeath(npc, mock(net.minecraft.world.damagesource.DamageSource.class));

        assertEquals(npcUuid, tracker.getNPCForPlayer(owner.getUUID()), "Items stay for the rejoin restore");
        assertFalse(tracker.isPendingDeath(owner.getUUID()), "The owner is not killed on next join");
        verify(npc, never()).spawnAtLocation(any(ServerLevel.class), any(net.minecraft.world.item.ItemStack.class));
    }

    @Test void npcIsLiveOnlyWithEntryAndRunningOwnerTag() {
        CombatTracker tracker = new CombatTracker(directory);
        UUID npcUuid = UUID.randomUUID();
        ServerPlayer owner = unauthenticatedPlayer(new UUID(0, 1));
        assertFalse(tracker.isNpcLive(npcUuid), "No entry");

        tracker.addNPC(owner.getUUID(), npcUuid, List.of(), level);
        assertFalse(tracker.isNpcLive(npcUuid), "Entry but owner never tagged");

        tracker.tag(owner, 60);
        assertTrue(tracker.isNpcLive(npcUuid));
        assertFalse(tracker.isNpcLive(UUID.randomUUID()), "Another NPC UUID");

        tracker.removeNPC(owner.getUUID());
        assertFalse(tracker.isNpcLive(npcUuid), "Entry consumed by the rejoin restore");
    }

    @Test void failedCombatSaveOnShutdownDoesNotEscapeOrSkipAuthSave() throws Exception {
        Path combatDir = directory.resolve("combat");
        Path authDir = directory.resolve("auth");
        java.nio.file.Files.createDirectories(combatDir);
        java.nio.file.Files.createDirectories(authDir);
        CombatTracker tracker = new CombatTracker(combatDir);
        AuthManager auth = new AuthManager(authDir);
        // A directory where the data file belongs makes the tracker write fail.
        Path blocked = combatDir.resolve("combat_persistence_data.json");
        java.nio.file.Files.createDirectory(blocked);
        java.nio.file.Files.writeString(blocked.resolve("prevent-replacement"), "keep");
        assertThrows(RuntimeException.class, tracker::stop, "Precondition: the combat save fails");

        assertDoesNotThrow(() -> Combatpersistence.saveOnShutdown(tracker, auth),
            "Vanilla's player and world saves run after this callback");
        try (var files = java.nio.file.Files.list(authDir)) {
            assertTrue(files.findAny().isPresent(), "The auth save still ran");
        }
    }

    @Test void emptyInventoryIsNotStashedToDisk() throws Exception {
        AuthManager auth = new AuthManager(directory);
        ServerPlayer player = unauthenticatedPlayer(new UUID(0, 7));
        var inventory = new net.minecraft.world.entity.player.Inventory(player, new net.minecraft.world.entity.EntityEquipment());
        when(player.getInventory()).thenReturn(inventory);
        Field menu = net.minecraft.world.entity.player.Player.class.getDeclaredField("inventoryMenu");
        menu.setAccessible(true);
        menu.set(player, new net.minecraft.world.inventory.InventoryMenu(inventory, false, player));
        player.containerMenu = player.inventoryMenu;

        auth.stashInventory(player);

        assertFalse(java.nio.file.Files.exists(directory.resolve("pending_inventories.json")),
            "No synchronous write for an empty snapshot");
    }

    private ServerPlayer unauthenticatedPlayer(UUID uuid) {
        ServerPlayer player = mock(ServerPlayer.class);
        ServerGamePacketListenerImpl connection = mock(ServerGamePacketListenerImpl.class);
        player.connection = connection;
        when(player.getUUID()).thenReturn(uuid);
        when(player.level()).thenReturn(level);
        when(player.getName()).thenReturn(Component.literal("test-player-" + uuid.getLeastSignificantBits()));
        // Like vanilla: the disconnect takes the player out of PlayerList.players.
        doAnswer(invocation -> livePlayers.remove(player)).when(connection).disconnect(any(Component.class));
        return player;
    }

    private static CombatNPC npc(UUID uuid) {
        CombatNPC npc = mock(CombatNPC.class);
        when(npc.getUUID()).thenReturn(uuid);
        return npc;
    }
}
