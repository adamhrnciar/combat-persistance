package com.pottersgame;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AuthManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File AUTH_FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "auth_data.json");
    private static final File LOC_FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "pending_locations.json");
    private static final File INV_FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "pending_inventories.json");

    private Map<UUID, UserData> users = new ConcurrentHashMap<>();
    private final Set<UUID> authenticatedPlayers = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private Map<UUID, OriginalLocation> preAuthLocations = new ConcurrentHashMap<>();
    // Inventory stashed (and hidden from the client) while a player is not yet
    // authenticated. Stored as Base64-encoded compressed item NBT, keyed by UUID.
    private Map<UUID, List<String>> preAuthInventories = new ConcurrentHashMap<>();

    public AuthManager() {
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
    }

    // Lockout is tracked per account AND per source IP: the per-account counter
    // stops brute-forcing one victim, the per-IP counter stops one host from
    // spraying attempts across many accounts (or many offline-mode names).
    private final Map<UUID, LoginAttempts> loginAttempts = new ConcurrentHashMap<>();
    private final Map<String, LoginAttempts> ipLoginAttempts = new ConcurrentHashMap<>();

    public enum LoginResult { SUCCESS, WRONG_PASSWORD, LOCKED_OUT }

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
        long hoursSinceLastLogin = (now - data.lastLoginTime) / (1000 * 60 * 60);

        long allowedHours = Combatpersistence.config.sessionDurationHours;

        if (currentIp.equals(data.lastIp) && hoursSinceLastLogin < allowedHours) {
            authenticatedPlayers.add(player.getUUID());
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

        public OriginalLocation(ServerPlayer player) {
            this.x = player.getX();
            this.y = player.getY();
            this.z = player.getZ();
            this.yaw = player.getYRot();
            this.pitch = player.getXRot();
            this.dimension = player.level().dimension().identifier().toString();
        }
    }

    public boolean isRegistered(UUID uuid) {
        return users.containsKey(uuid);
    }

    public boolean isAuthenticated(ServerPlayer player) {
        if (!authRequired(player.level().getServer())) return true;
        return authenticatedPlayers.contains(player.getUUID());
    }

    public void register(ServerPlayer player, String password) {
        String hash = BCrypt.hashpw(password, BCrypt.gensalt());
        UserData data = new UserData(hash, player.getName().getString(), getIp(player));
        users.put(player.getUUID(), data);
        authenticatedPlayers.add(player.getUUID());
        saveUsers();
    }

    public LoginResult login(ServerPlayer player, String password) {
        UUID uuid = player.getUUID();
        String ip = getIp(player);
        long now = System.currentTimeMillis();

        LoginAttempts accountAtt = loginAttempts.get(uuid);
        LoginAttempts ipAtt = ipLoginAttempts.get(ip);
        if ((accountAtt != null && accountAtt.lockedUntil > now)
                || (ipAtt != null && ipAtt.lockedUntil > now)) {
            return LoginResult.LOCKED_OUT;
        }

        UserData data = users.get(uuid);
        if (data != null && BCrypt.checkpw(password, data.passwordHash)) {
            loginAttempts.remove(uuid);
            ipLoginAttempts.remove(ip);
            data.lastIp = ip;
            data.lastLoginTime = now;
            data.username = player.getName().getString(); // backfill/refresh for name-based admin lookups
            authenticatedPlayers.add(uuid);
            saveUsers();
            return LoginResult.SUCCESS;
        }

        // Wrong password: record the failure and lock out once the limit is hit.
        boolean locked = recordFailure(loginAttempts.computeIfAbsent(uuid, k -> new LoginAttempts()), now)
                | recordFailure(ipLoginAttempts.computeIfAbsent(ip, k -> new LoginAttempts()), now);
        return locked ? LoginResult.LOCKED_OUT : LoginResult.WRONG_PASSWORD;
    }

    private static boolean recordFailure(LoginAttempts att, long now) {
        att.count++;
        if (att.count >= Combatpersistence.config.maxLoginAttempts) {
            att.lockedUntil = now + Combatpersistence.config.loginLockoutSeconds * 1000L;
            att.count = 0;
            return true;
        }
        return false;
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
    public void onAuthenticated(ServerPlayer player) {
        if (!authenticatedPlayers.contains(player.getUUID())) {
            authenticatedPlayers.add(player.getUUID());
        }

        // Keep the player frozen/invulnerable while we move and restore them, so
        // re-enabling gravity mid-restore can't drop them or deal fall damage.
        player.removeEffect(MobEffects.SLOWNESS);
        player.removeEffect(MobEffects.MINING_FATIGUE);

        // Pending death wins over everything: clear and kill before any restore.
        CombatEvents.handlePendingDeath(player);

        // Restore the base inventory we hid during the login window FIRST. This
        // also wipes any stale/duplicate live items, so the combat-NPC restore
        // below (which only ADDS items) can neither be erased nor doubled up.
        // A non-empty stash and an active combat NPC are mutually exclusive, so
        // these two restores never operate on overlapping items.
        restorePreAuthInventory(player);

        // Then add back items that were moved to a combat-log NPC, if any.
        CombatEvents.cleanUpNpc(player, Combatpersistence.tracker, player.level().getServer());

        restoreLocation(player);

        // Land them cleanly: only now is it safe to re-enable physics.
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
        player.setNoGravity(false);
        player.setInvulnerable(false);
    }

    public void logout(ServerPlayer player) {
        authenticatedPlayers.remove(player.getUUID());
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
        authenticatedPlayers.remove(uuid);
        loginAttempts.remove(uuid);
        boolean existed = users.remove(uuid) != null;
        if (existed) saveUsers();
        return existed;
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
            saveLocations();
        }
    }

    private void restoreLocation(ServerPlayer player) {
        OriginalLocation loc = preAuthLocations.remove(player.getUUID());
        if (loc != null) {
            saveLocations();
            ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, Identifier.parse(loc.dimension));
            ServerLevel currentLevel = (ServerLevel) player.level();
            ServerLevel targetDim = currentLevel.getServer().getLevel(dimKey);
            if (targetDim != null) {
                TeleportTransition transition = new TeleportTransition(
                    targetDim,
                    new Vec3(loc.x, loc.y, loc.z),
                    Vec3.ZERO,
                    loc.yaw,
                    loc.pitch,
                    TeleportTransition.DO_NOTHING
                );
                player.teleport(transition);
            }
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

        List<String> encoded = new ArrayList<>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            encodeStack(player.getInventory().getItem(i), world, encoded);
        }
        // Include the cursor/carried stack so an open-screen carry isn't lost.
        encodeStack(player.containerMenu.getCarried(), world, encoded);

        preAuthInventories.put(uuid, encoded);
        // Synchronous: durably persist the stash BEFORE destroying the live copy.
        saveInventories(false);
        clearLiveInventory(player);
    }

    public void restorePreAuthInventory(ServerPlayer player) {
        List<String> encoded = preAuthInventories.get(player.getUUID());
        if (encoded == null) return; // No stash (e.g. normal autologin) — leave live inventory untouched.
        ServerLevel world = player.level();

        // A stash existing means the live inventory should have been emptied at
        // stash time. Anything present is a stale duplicate of the stash, so wipe
        // it before restoring to guarantee a single copy.
        clearLiveInventory(player);

        for (String b64 : encoded) {
            try {
                byte[] bytes = Base64.getDecoder().decode(b64);
                CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
                ItemStack.CODEC.parse(world.registryAccess().createSerializationContext(NbtOps.INSTANCE), tag)
                    .resultOrPartial(e -> Combatpersistence.LOGGER.error("Failed to decode stashed item: {}", e))
                    .ifPresent(stack -> {
                        if (!player.getInventory().add(stack)) {
                            player.drop(stack, false);
                        }
                    });
            } catch (Exception e) {
                Combatpersistence.LOGGER.error("Failed to restore pre-auth inventory item", e);
            }
        }

        // Only now that the items are safely back in the live inventory do we drop
        // the stash, and persist that removal synchronously so a crash can't leave
        // a stash behind to be restored a second time (dupe).
        preAuthInventories.remove(player.getUUID());
        saveInventories(false);

        player.containerMenu.sendAllDataToRemote();
    }

    private static void encodeStack(ItemStack stack, ServerLevel world, List<String> out) {
        if (stack == null || stack.isEmpty()) return;
        try {
            CompoundTag tag = (CompoundTag) ItemStack.CODEC
                .encodeStart(world.registryAccess().createSerializationContext(NbtOps.INSTANCE), stack)
                .getOrThrow(e -> new RuntimeException("Failed to encode item: " + e));
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, baos);
            out.add(Base64.getEncoder().encodeToString(baos.toByteArray()));
        } catch (Exception e) {
            Combatpersistence.LOGGER.error("Failed to stash pre-auth inventory item", e);
        }
    }

    private static void clearLiveInventory(ServerPlayer player) {
        player.getInventory().clearContent();
        player.containerMenu.setCarried(ItemStack.EMPTY);
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
                if (loaded != null) users = loaded;
            } catch (Exception e) {
                Combatpersistence.LOGGER.error("Failed to load auth data", e);
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
        users.forEach((uuid, data) -> copy.put(uuid, new UserData(data)));
        Runnable write = () -> {
            try (FileWriter writer = new FileWriter(AUTH_FILE)) {
                GSON.toJson(copy, writer);
            } catch (IOException e) {
                Combatpersistence.LOGGER.error("Failed to save auth data", e);
            }
        };
        if (async) DiskIO.async(write); else DiskIO.sync(write);
    }

    private void loadLocations() {
        if (LOC_FILE.exists()) {
            try (FileReader reader = new FileReader(LOC_FILE)) {
                Type type = new TypeToken<ConcurrentHashMap<UUID, OriginalLocation>>(){}.getType();
                Map<UUID, OriginalLocation> loaded = GSON.fromJson(reader, type);
                if (loaded != null) preAuthLocations = loaded;
            } catch (Exception e) {
                Combatpersistence.LOGGER.error("Failed to load pending locations", e);
            }
        }
    }

    private void saveLocations() {
        saveLocations(true);
    }

    private void saveLocations(boolean async) {
        Map<UUID, OriginalLocation> copy = new HashMap<>(preAuthLocations);
        Runnable write = () -> {
            try (FileWriter writer = new FileWriter(LOC_FILE)) {
                GSON.toJson(copy, writer);
            } catch (IOException e) {
                Combatpersistence.LOGGER.error("Failed to save pending locations", e);
            }
        };
        if (async) DiskIO.async(write); else DiskIO.sync(write);
    }

    private void loadInventories() {
        if (INV_FILE.exists()) {
            try (FileReader reader = new FileReader(INV_FILE)) {
                Type type = new TypeToken<ConcurrentHashMap<UUID, List<String>>>(){}.getType();
                Map<UUID, List<String>> loaded = GSON.fromJson(reader, type);
                if (loaded != null) preAuthInventories = loaded;
            } catch (Exception e) {
                Combatpersistence.LOGGER.error("Failed to load pending inventories", e);
            }
        }
    }

    private void saveInventories(boolean async) {
        Map<UUID, List<String>> copy = new HashMap<>(preAuthInventories);
        Runnable write = () -> {
            try (FileWriter writer = new FileWriter(INV_FILE)) {
                GSON.toJson(copy, writer);
            } catch (IOException e) {
                Combatpersistence.LOGGER.error("Failed to save pending inventories", e);
            }
        };
        if (async) DiskIO.async(write); else DiskIO.sync(write);
    }
}
