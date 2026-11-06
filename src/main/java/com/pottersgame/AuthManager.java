package com.pottersgame;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.mindrot.jbcrypt.BCrypt;

import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

public class AuthManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final File AUTH_FILE;
    private final File LOC_FILE;
    private final File INV_FILE;

    private Map<UUID, UserData> users = new ConcurrentHashMap<>();
    // Authentication belongs to one connection, never to a reusable offline UUID.
    private final Map<UUID, net.minecraft.server.network.ServerGamePacketListenerImpl> authenticatedPlayers = new ConcurrentHashMap<>();
    private final Map<ServerPlayer, Object> pendingAuthentication = new HashMap<>();
    private static final ThreadPoolExecutor PASSWORD_WORKERS = new ThreadPoolExecutor(
        2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32), runnable -> {
            Thread thread = new Thread(runnable, "combatpersistence-passwords");
            thread.setDaemon(true);
            return thread;
        });
    private Map<UUID, OriginalLocation> preAuthLocations = new ConcurrentHashMap<>();
    // Inventory stashed (and hidden from the client) while a player is not yet
    // authenticated. Stored as slot + Base64-encoded compressed item NBT,
    // keyed by UUID, so armor/offhand go back to their equipment slots.
    private Map<UUID, List<SlottedInventory.SlotItem>> preAuthInventories = new ConcurrentHashMap<>();

    public AuthManager() {
        this(FabricLoader.getInstance().getConfigDir());
    }

    AuthManager(java.nio.file.Path directory) {
        AUTH_FILE = directory.resolve("auth_data.json").toFile();
        LOC_FILE = directory.resolve("pending_locations.json").toFile();
        INV_FILE = directory.resolve("pending_inventories.json").toFile();
        loadUsers();
        loadLocations();
        loadInventories();
    }

    public static class UserData {
        public String passwordHash;
        public String username;
        public String lastIp;
        public long lastLoginTime;
        public String customSkin;

        public UserData(String hash, String name, String ip) {
            this.passwordHash = hash;
            this.username = name;
            this.lastIp = ip;
            this.lastLoginTime = System.currentTimeMillis();
        }

        /** Snapshot copy so async serialization never races live mutation. */
        UserData(UserData other) {
            this.passwordHash = other.passwordHash;
            this.username = other.username;
            this.lastIp = other.lastIp;
            this.lastLoginTime = other.lastLoginTime;
            this.customSkin = other.customSkin;
        }
    }

    /** Tracks consecutive failed /login attempts for brute-force lockout. In-memory only. */
    private static class LoginAttempts {
        int count;
        long lockedUntil;
        long lastFailureTime;
    }

    // Lockout is tracked per account AND per source IP: the per-account counter
    // stops brute-forcing one victim, the per-IP counter stops one host from
    // spraying attempts across many accounts (or many offline-mode names).
    private final Map<UUID, LoginAttempts> loginAttempts = new ConcurrentHashMap<>();
    private final Map<String, LoginAttempts> ipLoginAttempts = new ConcurrentHashMap<>();

    public enum LoginResult { SUCCESS, WRONG_PASSWORD, LOCKED_OUT, BUSY, ERROR }

    /**
     * Whether the password-auth flow applies on this server at all. Online-mode
     * servers already have Mojang-verified identities, so password auth only
     * guards offline-mode servers (where anyone can join under any name), and
     * only while forceAuthInOfflineMode is enabled.
     *
     * Every auth check must go through this (via isAuthenticated) — gating only
     * the join flow while other checks consult the raw authenticated set would
     * leave players on online-mode servers permanently "unauthenticated".
     */
    public boolean authRequired(MinecraftServer server) {
        if (!Combatpersistence.config.enableAuth) return false;
        return !server.usesAuthentication() && Combatpersistence.config.forceAuthInOfflineMode;
    }

    public boolean checkAutoLogin(ServerPlayer player) {
        // IP auto-login is opt-in; without it players re-enter their password each session.
        if (!Combatpersistence.config.enableIpAutoLogin) return false;

        UserData data = users.get(player.getUUID());
        if (data == null) return false;

        String currentIp = getIp(player);
        long now = System.currentTimeMillis();
        long elapsed = now - data.lastLoginTime;
        long allowedMillis = Math.max(0L, Combatpersistence.config.sessionDurationHours) * 3_600_000L;

        if (elapsed >= 0 && elapsed < allowedMillis && currentIp.equals(data.lastIp)) {
            authenticatedPlayers.put(player.getUUID(), player.connection);
            // Update last login time to extend the session
            data.lastLoginTime = now;
            saveUsers();
            return true;
        }
        return false;
    }

    public static class OriginalLocation {
        public double x, y, z;
        public float yaw, pitch;
        public String dimension;
        public String deathDimension;
        public int deathX, deathY, deathZ;

        public OriginalLocation(ServerPlayer player) {
            this.x = player.getX();
            this.y = player.getY();
            this.z = player.getZ();
            this.yaw = player.getYRot();
            this.pitch = player.getXRot();
            this.dimension = player.level().dimension().identifier().toString();
            player.getLastDeathLocation().ifPresent(death -> {
                this.deathDimension = death.dimension().identifier().toString();
                this.deathX = death.pos().getX();
                this.deathY = death.pos().getY();
                this.deathZ = death.pos().getZ();
            });
        }
    }

    public boolean isRegistered(UUID uuid) {
        return users.containsKey(uuid);
    }

    public boolean isAuthenticated(ServerPlayer player) {
        if (!authRequired(player.level().getServer())) return true;
        return player.connection != null && authenticatedPlayers.get(player.getUUID()) == player.connection;
    }

    /** BCrypt's limit is UTF-8 bytes, not Java characters. */
    public static boolean isPasswordWithinLimit(String password, int characterLimit) {
        return password.length() <= characterLimit
            && password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    /** Start work on the server thread; workers only hash immutable captured strings. */
    public void authenticate(ServerPlayer player, String password, boolean registration,
                             Consumer<LoginResult> completion) {
        if (!isCurrentConnection(player) || isAuthenticated(player)) return;
        if (pendingAuthentication.containsKey(player)) {
            completion.accept(LoginResult.BUSY);
            return;
        }
        if (getLockoutSecondsRemaining(player) > 0) {
            completion.accept(LoginResult.LOCKED_OUT);
            return;
        }
        UserData expected = users.get(player.getUUID());
        if (registration && expected != null) {
            completion.accept(LoginResult.ERROR);
            return;
        }
        if (!isPasswordWithinLimit(password, Combatpersistence.config.maxPasswordLength)) {
            completion.accept(LoginResult.WRONG_PASSWORD);
            return;
        }
        pruneAttempts();
        if (loginAttempts.size() >= 10000 || ipLoginAttempts.size() >= 10000) {
            completion.accept(LoginResult.BUSY);
            return;
        }
        Object request = new Object();
        pendingAuthentication.put(player, request);
        MinecraftServer server = player.level().getServer();
        String expectedHash = expected == null ? null : expected.passwordHash;
        try {
            PASSWORD_WORKERS.execute(() -> {
                String newHash = null;
                boolean matches = false;
                boolean failed = false;
                try {
                    if (registration) newHash = BCrypt.hashpw(password, BCrypt.gensalt());
                    else if (expectedHash != null) matches = BCrypt.checkpw(password, expectedHash);
                } catch (RuntimeException exception) {
                    // Do not include command text, passwords, or hashes in diagnostics.
                    Combatpersistence.LOGGER.error("Password verification failed; account remains locked");
                    failed = true;
                }
                String hash = newHash;
                boolean verified = matches;
                boolean error = failed;
                server.execute(() -> {
                    if (pendingAuthentication.get(player) != request) return;
                    pendingAuthentication.remove(player);
                    // Disconnect, reset, and replacement connections invalidate old work.
                    if (!isCurrentConnection(player) || users.get(player.getUUID()) != expected) return;
                    if (error) {
                        completion.accept(LoginResult.ERROR);
                        return;
                    }
                    if (getLockoutSecondsRemaining(player) > 0) {
                        completion.accept(LoginResult.LOCKED_OUT);
                        return;
                    }
                    UUID uuid = player.getUUID();
                    String ip = getIp(player);
                    if (registration || verified) {
                        UserData updated = registration
                            ? new UserData(hash, player.getName().getString(), null)
                            : new UserData(expected);
                        updated.lastIp = Combatpersistence.config.enableIpAutoLogin ? ip : null;
                        updated.lastLoginTime = System.currentTimeMillis();
                        updated.username = player.getName().getString();
                        users.put(uuid, updated);
                        try {
                            // A successful registration must survive a crash before gameplay starts.
                            saveUsers(false);
                        } catch (RuntimeException exception) {
                            if (expected == null) users.remove(uuid, updated);
                            else users.put(uuid, expected);
                            completion.accept(LoginResult.ERROR);
                            return;
                        }
                        loginAttempts.remove(uuid);
                        ipLoginAttempts.remove(ip);
                        authenticatedPlayers.put(uuid, player.connection);
                        completion.accept(LoginResult.SUCCESS);
                    } else {
                        long now = System.currentTimeMillis();
                        boolean locked = recordFailure(loginAttempts.computeIfAbsent(uuid, k -> new LoginAttempts()), now)
                            | recordFailure(ipLoginAttempts.computeIfAbsent(ip, k -> new LoginAttempts()), now);
                        completion.accept(locked ? LoginResult.LOCKED_OUT : LoginResult.WRONG_PASSWORD);
                    }
                });
            });
        } catch (RejectedExecutionException exception) {
            pendingAuthentication.remove(player, request);
            completion.accept(LoginResult.BUSY);
        }
    }

    private static boolean isCurrentConnection(ServerPlayer player) {
        // A dead player may be removed from the level while still owning the
        // active listener and player-list entry. They must authenticate before
        // respawn; listener identity and current player identity reject stale work.
        return player.connection != null && player.connection.isAcceptingMessages()
            && player.level().getServer().getPlayerList().getPlayer(player.getUUID()) == player;
    }

    private static boolean recordFailure(LoginAttempts att, long now) {
        if (now - att.lastFailureTime > 600_000L && att.lockedUntil <= now) att.count = 0;
        att.lastFailureTime = now;
        att.count++;
        if (att.count >= Combatpersistence.config.maxLoginAttempts) {
            att.lockedUntil = now + Combatpersistence.config.loginLockoutSeconds * 1000L;
            att.count = 0;
            return true;
        }
        return false;
    }

    private void pruneAttempts() {
        long now = System.currentTimeMillis();
        loginAttempts.values().removeIf(attempt -> attempt.lockedUntil <= now
            && now - attempt.lastFailureTime > 600_000L);
        ipLoginAttempts.values().removeIf(attempt -> attempt.lockedUntil <= now
            && now - attempt.lastFailureTime > 600_000L);
    }

    /** Seconds remaining on a login lockout for this player (account or source IP), or 0 if not locked out. */
    public int getLockoutSecondsRemaining(ServerPlayer player) {
        long lockedUntil = 0;
        LoginAttempts att = loginAttempts.get(player.getUUID());
        if (att != null) lockedUntil = att.lockedUntil;
        LoginAttempts ipAtt = ipLoginAttempts.get(getIp(player));
        if (ipAtt != null) lockedUntil = Math.max(lockedUntil, ipAtt.lockedUntil);
        long remaining = lockedUntil - System.currentTimeMillis();
        return remaining > 0 ? (int) Math.ceil(remaining / 1000.0) : 0;
    }

    // This MUST be called on the Server Main Thread
    public boolean onAuthenticated(ServerPlayer player) {
        if (!isCurrentConnection(player) || authenticatedPlayers.get(player.getUUID()) != player.connection) return false;
        try {

        // Keep the player frozen/invulnerable while we move and restore them, so
        // re-enabling gravity mid-restore can't drop them or deal fall damage.
        player.removeEffect(MobEffects.SLOWNESS);
        player.removeEffect(MobEffects.MINING_FATIGUE);

        // Pending death wins over everything: clear and kill before any restore.
        restoreLocation(player);
        if (Combatpersistence.tracker.isPendingDeath(player.getUUID())) {
            CombatEvents.handlePendingDeath(player);
            player.setNoGravity(false);
            player.setPermanentlyInvulnerable(false);
            return true;
        }

        // Restore the base inventory we hid during the login window FIRST. This
        // also wipes any stale/duplicate live items, so the combat-NPC restore
        // below (which only ADDS items) can neither be erased nor doubled up.
        // A non-empty stash and an active combat NPC are mutually exclusive, so
        // these two restores never operate on overlapping items.
        restorePreAuthInventory(player);

        // Then add back items that were moved to a combat-log NPC, if any.
        CombatEvents.cleanUpNpc(player, Combatpersistence.tracker, player.level().getServer());

        // Land them cleanly: only now is it safe to re-enable physics.
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
        player.setNoGravity(false);
        player.setPermanentlyInvulnerable(false);
        return true;
        } catch (RuntimeException exception) {
            authenticatedPlayers.remove(player.getUUID(), player.connection);
            Combatpersistence.LOGGER.error("Could not restore authenticated player state; connection refused", exception);
            player.connection.disconnect(net.minecraft.network.chat.Component.literal(
                "Authentication state could not be restored. Please contact an operator."));
            return false;
        }
    }

    public void logout(ServerPlayer player) {
        authenticatedPlayers.remove(player.getUUID(), player.connection);
        pendingAuthentication.remove(player);
    }

    /**
     * Operator action: wipe a player's registration so they must register again
     * with a new password. Also clears any live authenticated session for that
     * UUID. Returns true if a registration existed and was removed.
     *
     * The caller should disconnect the player (if online) afterwards so they
     * re-enter the normal join/lobby flow cleanly on reconnect.
     */
    public boolean resetUser(UUID uuid) {
        UserData previous = users.remove(uuid);
        if (previous != null) {
            try {
                saveUsers(false);
            } catch (RuntimeException error) {
                users.put(uuid, previous);
                throw error;
            }
        }
        // Invalidate sessions and pending work only after the account change
        // commits. A failed reset must not unlock registration in memory.
        authenticatedPlayers.remove(uuid);
        pendingAuthentication.keySet().removeIf(player -> player.getUUID().equals(uuid));
        loginAttempts.remove(uuid);
        return previous != null;
    }

    /**
     * Resolve a registered player's UUID from a name, for operator commands that
     * must work on offline players too. Tries stored usernames first, then (on
     * offline-mode servers) the deterministic offline UUID vanilla derives from
     * the name. Returns null if no matching registration exists.
     */
    public UUID findRegisteredUuidByName(String name, boolean onlineMode) {
        for (Map.Entry<UUID, UserData> e : users.entrySet()) {
            if (name.equalsIgnoreCase(e.getValue().username)) {
                return e.getKey();
            }
        }
        if (!onlineMode) {
            UUID offline = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            if (users.containsKey(offline)) return offline;
        }
        return null;
    }

    public void saveLocation(ServerPlayer player) {
        if (!preAuthLocations.containsKey(player.getUUID())) {
            preAuthLocations.put(player.getUUID(), new OriginalLocation(player));
            try {
                saveLocations(false);
            } catch (RuntimeException exception) {
                preAuthLocations.remove(player.getUUID());
                throw exception;
            }
        }
        // Initial spawn packets also contain the last death coordinate.
        player.setLastDeathLocation(Optional.empty());
    }

    public void restoreLocation(ServerPlayer player) {
        OriginalLocation loc = preAuthLocations.get(player.getUUID());
        if (loc == null) return;
        ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, Identifier.parse(loc.dimension));
        ServerLevel targetDim = player.level().getServer().getLevel(dimKey);
        if (targetDim == null) throw new IllegalStateException("Original player dimension is unavailable");
        player.teleport(new TeleportTransition(targetDim, new Vec3(loc.x, loc.y, loc.z),
            Vec3.ZERO, loc.yaw, loc.pitch, TeleportTransition.DO_NOTHING));
        if (loc.deathDimension != null) {
            ResourceKey<Level> deathDimension = ResourceKey.create(Registries.DIMENSION, Identifier.parse(loc.deathDimension));
            player.setLastDeathLocation(Optional.of(net.minecraft.core.GlobalPos.of(deathDimension,
                new net.minecraft.core.BlockPos(loc.deathX, loc.deathY, loc.deathZ))));
        }
        PlayerDataPersistence.save(player);
        preAuthLocations.remove(player.getUUID());
        try {
            saveLocations(false);
        } catch (RuntimeException exception) {
            preAuthLocations.put(player.getUUID(), loc);
            throw exception;
        }
    }

    /**
     * Hides a not-yet-authenticated player's inventory from their client by
     * serializing it server-side and clearing the live inventory. This prevents
     * an impostor (who shares an offline UUID with a registered player by simply
     * using their name) from seeing the victim's items during the login window.
     *
     * Dupe/loss safety:
     *  - The stash is the single source of truth. Once a stash exists for a UUID,
     *    the live inventory is ALWAYS cleared on join until it is restored, so a
     *    stale copy reloaded by vanilla after an unclean shutdown can never be
     *    duplicated on top of the stash.
     *  - The stash file is written SYNCHRONOUSLY before the live inventory is
     *    cleared, so the items are durably backed up before the only other copy
     *    is destroyed (no loss if the server dies mid-stash).
     *  - The carried/cursor stack is stashed too, never silently discarded.
     *
     * Must be called on the server main thread.
     */
    public void stashInventory(ServerPlayer player) {
        UUID uuid = player.getUUID();
        if (preAuthInventories.containsKey(uuid)) {
            // A stash is already authoritative for this player. Whatever is in the
            // live inventory now is a stale duplicate (e.g. vanilla reloaded it
            // after a crash before player.dat was overwritten) — drop it so it
            // cannot be duplicated when the stash is restored.
            clearLiveInventory(player);
            return;
        }
        ServerLevel world = player.level();

        // Slot-aware snapshot: main storage with indices, armor/offhand via the
        // high container indices, cursor/crafting as slotless extras.
        List<SlottedInventory.SlotItem> encoded =
            SlottedInventory.encodeAll(world, SlottedInventory.snapshot(player));

        preAuthInventories.put(uuid, encoded);
        // Synchronous: durably persist the stash BEFORE destroying the live copy.
        try {
            saveInventories(false);
        } catch (RuntimeException exception) {
            preAuthInventories.remove(uuid, encoded);
            throw exception;
        }
        clearLiveInventory(player);
        Combatpersistence.debug("Stashed pre-auth inventory of {} ({} items)", player.getName().getString(), encoded.size());
    }

    public void restorePreAuthInventory(ServerPlayer player) {
        List<SlottedInventory.SlotItem> encoded = preAuthInventories.get(player.getUUID());
        if (encoded == null) return; // No stash (e.g. normal autologin) — leave live inventory untouched.
        ServerLevel world = player.level();

        SlottedInventory.DecodeResult decoded = SlottedInventory.decodeAll(world, encoded);

        // Partial decode must not release a second live copy or discard unreadable items.
        if (decoded.failures() != 0) {
            throw new IllegalStateException("Pending inventory could not be decoded completely");
        }
        SlottedInventory.PlaceResult result = SlottedInventory.place(player, decoded.items(), true);
        // Commit player.dat before consuming the authoritative stash. A crash between
        // these writes leaves the stash authoritative and replaces the live copy.
        PlayerDataPersistence.save(player);
        preAuthInventories.remove(player.getUUID());
        try {
            saveInventories(false);
        } catch (RuntimeException exception) {
            preAuthInventories.put(player.getUUID(), encoded);
            throw exception;
        }
        result.dropOverflow(player);
    }

    /** Offline death consumes any stale login stash without releasing its items. */
    public void discardPreAuthInventory(UUID uuid) {
        List<SlottedInventory.SlotItem> saved = preAuthInventories.remove(uuid);
        if (saved == null) return;
        try {
            saveInventories(false);
        } catch (RuntimeException error) {
            preAuthInventories.put(uuid, saved);
            throw error;
        }
    }

    private static void clearLiveInventory(ServerPlayer player) {
        SlottedInventory.clearLiveInventory(player);
    }

    public void setCustomSkin(UUID uuid, String skinName) {
        UserData data = users.get(uuid);
        if (data != null) {
            data.customSkin = skinName;
            saveUsers();
        }
    }

    public String getCustomSkin(UUID uuid) {
        UserData data = users.get(uuid);
        return (data != null) ? data.customSkin : null;
    }

    private String getIp(ServerPlayer player) {
        // InetSocketAddress handles IPv6 correctly; naive string splitting on ':'
        // mangled IPv6 addresses into useless fragments.
        if (player.connection.getRemoteAddress() instanceof InetSocketAddress inet && inet.getAddress() != null) {
            return inet.getAddress().getHostAddress();
        }
        String addr = player.connection.getRemoteAddress().toString();
        if (addr.startsWith("/")) addr = addr.substring(1);
        return addr.split(":")[0];
    }

    /** Synchronously flush all auth state to disk. Called on server shutdown. */
    public void flush() {
        saveUsers(false);
        saveLocations(false);
        saveInventories(false);
    }

    private void loadUsers() {
        if (AUTH_FILE.exists()) {
            try (FileReader reader = new FileReader(AUTH_FILE)) {
                Type type = new TypeToken<ConcurrentHashMap<UUID, UserData>>(){}.getType();
                Map<UUID, UserData> loaded = GSON.fromJson(reader, type);
                if (loaded == null || loaded.values().stream().anyMatch(data -> data == null || data.passwordHash == null)) {
                    throw new IllegalStateException("Invalid account data");
                }
                users = loaded;
            } catch (Exception e) {
                throw new IllegalStateException("Cannot safely load auth data; refusing to start with empty accounts", e);
            }
        }
    }

    private void saveUsers() {
        saveUsers(true);
    }

    private void saveUsers(boolean async) {
        // Deep copy: UserData objects are mutated on login, which could race the
        // background serialization of a shallow snapshot.
        Map<UUID, UserData> copy = new HashMap<>();
        users.forEach((uuid, data) -> {
            UserData saved = new UserData(data);
            if (Combatpersistence.config != null && !Combatpersistence.config.enableIpAutoLogin) saved.lastIp = null;
            copy.put(uuid, saved);
        });
        Runnable write = () -> {
            DiskIO.writeJson(AUTH_FILE, GSON, copy);
        };
        if (async) DiskIO.async(write); else DiskIO.sync(write);
    }

    private void loadLocations() {
        if (LOC_FILE.exists()) {
            try (FileReader reader = new FileReader(LOC_FILE)) {
                Type type = new TypeToken<ConcurrentHashMap<UUID, OriginalLocation>>(){}.getType();
                Map<UUID, OriginalLocation> loaded = GSON.fromJson(reader, type);
                if (loaded == null) throw new IllegalStateException("Invalid pending locations");
                for (OriginalLocation location : loaded.values()) {
                    if (location == null || location.dimension == null
                            || !Double.isFinite(location.x) || !Double.isFinite(location.y)
                            || !Double.isFinite(location.z) || !Float.isFinite(location.yaw)
                            || !Float.isFinite(location.pitch)) {
                        throw new IllegalStateException("Invalid pending location entry");
                    }
                    Identifier.parse(location.dimension);
                    if (location.deathDimension != null) Identifier.parse(location.deathDimension);
                }
                preAuthLocations = loaded;
            } catch (Exception e) {
                throw new IllegalStateException("Cannot safely load pending locations", e);
            }
        }
    }

    private void saveLocations() {
        saveLocations(true);
    }

    private void saveLocations(boolean async) {
        Map<UUID, OriginalLocation> copy = new HashMap<>(preAuthLocations);
        Runnable write = () -> {
            DiskIO.writeJson(LOC_FILE, GSON, copy);
        };
        if (async) DiskIO.async(write); else DiskIO.sync(write);
    }

    private void loadInventories() {
        if (INV_FILE.exists()) {
            try (FileReader reader = new FileReader(INV_FILE)) {
                JsonElement root = JsonParser.parseReader(reader);
                if (root.isJsonObject() && root.getAsJsonObject().has("inventories")) {
                    // v2: {"formatVersion": 2, "inventories": {uuid: [{"slot": n, "nbt": "..."}]}}
                    JsonElement format = root.getAsJsonObject().get("formatVersion");
                    if (format == null || !format.isJsonPrimitive()
                            || !format.getAsJsonPrimitive().isNumber()
                            || !format.getAsString().equals("2")) {
                        throw new IllegalStateException("Unsupported pending inventory format");
                    }
                    Type type = new TypeToken<ConcurrentHashMap<UUID, List<SlottedInventory.SlotItem>>>(){}.getType();
                    Map<UUID, List<SlottedInventory.SlotItem>> loaded =
                        GSON.fromJson(root.getAsJsonObject().get("inventories"), type);
                    if (loaded == null) throw new IllegalStateException("Invalid pending inventories");
                    preAuthInventories = loaded;
                } else {
                    // Legacy pre-slot format: bare {uuid: ["b64", ...]} map. Convert in
                    // memory to slotless entries; the next save writes the v2 format.
                    Type type = new TypeToken<ConcurrentHashMap<UUID, List<String>>>(){}.getType();
                    Map<UUID, List<String>> legacy = GSON.fromJson(root, type);
                    if (legacy == null) throw new IllegalStateException("Invalid legacy inventory data");
                    if (legacy != null) {
                        Map<UUID, List<SlottedInventory.SlotItem>> converted = new ConcurrentHashMap<>();
                        legacy.forEach((uuid, list) -> {
                            List<SlottedInventory.SlotItem> items = new ArrayList<>();
                            if (list == null) throw new IllegalStateException("Invalid legacy inventory entry");
                            if (list != null) {
                                for (String b64 : list) {
                                    items.add(new SlottedInventory.SlotItem(SlottedInventory.NO_SLOT, b64));
                                }
                            }
                            converted.put(uuid, items);
                        });
                        preAuthInventories = converted;
                        if (!converted.isEmpty()) {
                            Combatpersistence.LOGGER.info(
                                "Migrated {} legacy pre-auth inventory stash(es) to the slotted format (restored slotless)",
                                converted.size());
                        }
                    }
                }
                for (List<SlottedInventory.SlotItem> items : preAuthInventories.values()) {
                    if (items == null || items.stream().anyMatch(item ->
                            item == null || item.nbt == null || item.nbt.isBlank())) {
                        throw new IllegalStateException("Invalid pending inventory entry");
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("Cannot safely load pending inventories", e);
            }
        }
    }

    private void saveInventories(boolean async) {
        Map<UUID, List<SlottedInventory.SlotItem>> copy = new HashMap<>(preAuthInventories);
        Runnable write = () -> {
            JsonObject root = new JsonObject();
            root.addProperty("formatVersion", 2);
            root.add("inventories", GSON.toJsonTree(copy));
            DiskIO.writeJson(INV_FILE, GSON, root);
        };
        if (async) DiskIO.async(write); else DiskIO.sync(write);
    }
}
