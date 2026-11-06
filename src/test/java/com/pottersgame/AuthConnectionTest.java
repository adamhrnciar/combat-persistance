package com.pottersgame;

import com.google.gson.Gson;
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
import org.mindrot.jbcrypt.BCrypt;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthConnectionTest {
    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000042");
    private static final String TEST_PASSWORD = "test-password-only";
    private static final String TEST_HASH = BCrypt.hashpw(TEST_PASSWORD, BCrypt.gensalt(4));

    @TempDir static Path bootstrapDirectory;
    @TempDir Path directory;
    private CombatConfig previousConfig;

    @BeforeAll static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // A plain JUnit JVM has no Fabric game provider. Initialize the mod's
        // static managers against an isolated directory, never a real account store.
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

    @BeforeEach void requirePasswordAuthentication() {
        previousConfig = Combatpersistence.config;
        CombatConfig config = new CombatConfig();
        config.enableAuth = true;
        config.forceAuthInOfflineMode = true;
        config.enableIpAutoLogin = false;
        Combatpersistence.config = config;
    }

    @AfterEach void restoreConfig() {
        Combatpersistence.config = previousConfig;
    }

    @Test void authenticationSurvivesRespawnButDoesNotTransferToReconnect() throws Exception {
        Fixture fixture = new Fixture(true);
        fixture.loginAndCommit();
        assertEquals(List.of(AuthManager.LoginResult.SUCCESS), fixture.results);
        assertTrue(fixture.manager.isAuthenticated(fixture.player));

        ServerPlayer respawned = fixture.player(fixture.player.connection);
        when(fixture.playerList.getPlayer(PLAYER_ID)).thenReturn(respawned);
        assertTrue(fixture.manager.isAuthenticated(respawned),
            "Respawn keeps the authenticated packet listener");

        ServerPlayer reconnected = fixture.player(fixture.connection());
        when(fixture.playerList.getPlayer(PLAYER_ID)).thenReturn(reconnected);
        assertFalse(fixture.manager.isAuthenticated(reconnected),
            "A reused offline UUID does not authenticate another connection");
    }

    @Test void deadPlayerWithCurrentConnectionCanAuthenticateBeforeRespawn() throws Exception {
        Fixture fixture = new Fixture(true);
        Field removalReason = net.minecraft.world.entity.Entity.class.getDeclaredField("removalReason");
        removalReason.setAccessible(true);
        removalReason.set(fixture.player, net.minecraft.world.entity.Entity.RemovalReason.KILLED);
        assertTrue(fixture.player.isRemoved());
        fixture.loginAndCommit();
        assertEquals(List.of(AuthManager.LoginResult.SUCCESS), fixture.results);
        assertTrue(fixture.manager.isAuthenticated(fixture.player));
    }

    @Test void lateLogoutCannotRevokeReplacementConnectionsAuthentication() throws Exception {
        Fixture fixture = new Fixture(true);
        fixture.loginAndCommit();
        fixture.manager.logout(fixture.player);

        ServerPlayer replacement = fixture.player(fixture.connection());
        when(fixture.playerList.getPlayer(PLAYER_ID)).thenReturn(replacement);
        fixture.manager.authenticate(replacement, TEST_PASSWORD, false, fixture.results::add);
        fixture.awaitCompletion().run();
        assertTrue(fixture.manager.isAuthenticated(replacement));

        fixture.manager.logout(fixture.player);
        assertTrue(fixture.manager.isAuthenticated(replacement),
            "Cleanup for the old listener must not remove the new listener's session");
    }

    @Test void passwordCompletionAfterDisconnectCannotAuthenticatePlayer() throws Exception {
        Fixture fixture = new Fixture(true);
        String originalStore = Files.readString(directory.resolve("auth_data.json"));
        fixture.manager.authenticate(fixture.player, TEST_PASSWORD, false, fixture.results::add);
        Runnable completion = fixture.awaitCompletion();

        when(fixture.player.connection.isAcceptingMessages()).thenReturn(false);
        completion.run();

        assertTrue(fixture.results.isEmpty(), "Disconnected clients receive no successful login callback");
        assertFalse(fixture.manager.isAuthenticated(fixture.player));
        assertEquals(originalStore, Files.readString(directory.resolve("auth_data.json")));
    }

    @Test void passwordCompletionCannotAuthenticateReplacementPlayer() throws Exception {
        Fixture fixture = new Fixture(true);
        fixture.manager.authenticate(fixture.player, TEST_PASSWORD, false, fixture.results::add);
        Runnable completion = fixture.awaitCompletion();

        ServerPlayer replacement = fixture.player(fixture.connection());
        when(fixture.playerList.getPlayer(PLAYER_ID)).thenReturn(replacement);
        completion.run();

        assertTrue(fixture.results.isEmpty());
        assertFalse(fixture.manager.isAuthenticated(fixture.player));
        assertFalse(fixture.manager.isAuthenticated(replacement));
    }

    @Test void accountResetInvalidatesPasswordWorkAlreadyQueuedForCommit() throws Exception {
        Fixture fixture = new Fixture(true);
        fixture.manager.authenticate(fixture.player, TEST_PASSWORD, false, fixture.results::add);
        Runnable completion = fixture.awaitCompletion();

        assertTrue(fixture.manager.resetUser(PLAYER_ID));
        completion.run();

        assertTrue(fixture.results.isEmpty());
        assertFalse(fixture.manager.isRegistered(PLAYER_ID));
        assertFalse(fixture.manager.isAuthenticated(fixture.player));
        assertFalse(new AuthManager(directory).isRegistered(PLAYER_ID),
            "Stale password work cannot recreate the reset account on disk");
    }

    @Test void failedAccountResetPreservesRegistrationAndLiveSession() throws Exception {
        Fixture fixture = new Fixture(true);
        fixture.loginAndCommit();
        Path store = directory.resolve("auth_data.json");
        Path backup = directory.resolve("auth_data.backup.json");
        String committedAccount = Files.readString(store);
        Files.move(store, backup);
        Files.createDirectory(store);
        Files.writeString(store.resolve("prevent-replacement"), "keep original account authoritative");

        assertThrows(RuntimeException.class, () -> fixture.manager.resetUser(PLAYER_ID));
        assertTrue(fixture.manager.isRegistered(PLAYER_ID), "Failed reset must not permit new registration");
        assertTrue(fixture.manager.isAuthenticated(fixture.player), "Failed reset must not revoke the owner's live session");
        assertEquals(committedAccount, Files.readString(backup));

        Files.delete(store.resolve("prevent-replacement"));
        Files.delete(store);
        Files.move(backup, store);
        assertTrue(new AuthManager(directory).isRegistered(PLAYER_ID));
        var accounts = new Gson().fromJson(Files.readString(store), com.google.gson.JsonObject.class);
        AuthManager.UserData restored = new Gson().fromJson(accounts.getAsJsonObject(PLAYER_ID.toString()), AuthManager.UserData.class);
        assertNotNull(restored);
        assertEquals(TEST_HASH, restored.passwordHash);
    }

    @Test void logoutInvalidatesRegistrationWorkAlreadyQueuedForCommit() throws Exception {
        Fixture fixture = new Fixture(false);
        fixture.manager.authenticate(fixture.player, TEST_PASSWORD, true, fixture.results::add);
        Runnable completion = fixture.awaitCompletion();

        fixture.manager.logout(fixture.player);
        completion.run();

        assertTrue(fixture.results.isEmpty());
        assertFalse(fixture.manager.isRegistered(PLAYER_ID));
        assertFalse(fixture.manager.isAuthenticated(fixture.player));
        assertFalse(Files.exists(directory.resolve("auth_data.json")),
            "Abandoned registration must not reserve a player name");
    }

    private class Fixture {
        final MinecraftServer server = mock(MinecraftServer.class);
        final ServerLevel level = mock(ServerLevel.class);
        final PlayerList playerList = mock(PlayerList.class);
        final BlockingQueue<Runnable> completions = new LinkedBlockingQueue<>();
        final List<AuthManager.LoginResult> results = new ArrayList<>();
        final AuthManager manager;
        final ServerPlayer player;

        Fixture(boolean registered) throws Exception {
            if (registered) {
                AuthManager.UserData account = new AuthManager.UserData(TEST_HASH, "test-player", null);
                Files.writeString(directory.resolve("auth_data.json"),
                    new Gson().toJson(Map.of(PLAYER_ID, account)));
            }
            manager = new AuthManager(directory);
            when(level.getServer()).thenReturn(server);
            when(server.getPlayerList()).thenReturn(playerList);
            doAnswer(invocation -> {
                completions.add(invocation.getArgument(0, Runnable.class));
                return null;
            }).when(server).execute(any(Runnable.class));
            player = player(connection());
            when(playerList.getPlayer(PLAYER_ID)).thenReturn(player);
        }

        ServerGamePacketListenerImpl connection() {
            ServerGamePacketListenerImpl connection = mock(ServerGamePacketListenerImpl.class);
            when(connection.isAcceptingMessages()).thenReturn(true);
            when(connection.getRemoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 25565));
            return connection;
        }

        ServerPlayer player(ServerGamePacketListenerImpl connection) {
            ServerPlayer player = mock(ServerPlayer.class);
            player.connection = connection;
            when(player.getUUID()).thenReturn(PLAYER_ID);
            when(player.level()).thenReturn(level);
            when(player.getName()).thenReturn(Component.literal("test-player"));
            return player;
        }

        Runnable awaitCompletion() throws InterruptedException {
            Runnable completion = completions.poll(20, TimeUnit.SECONDS);
            assertNotNull(completion, "Password worker did not queue its main-thread completion");
            return completion;
        }

        void loginAndCommit() throws InterruptedException {
            manager.authenticate(player, TEST_PASSWORD, false, results::add);
            awaitCompletion().run();
        }
    }
}
