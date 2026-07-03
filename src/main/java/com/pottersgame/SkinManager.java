package com.pottersgame;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.pottersgame.mixin.PlayerAccessor;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public class SkinManager {

    /** Cached lookup result; {@code property == null} caches a failed lookup so bad names don't re-hit the API. */
    private record CacheEntry(Property property, long fetchedAt) {}

    private static final Map<String, CacheEntry> skinCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 6 * 60 * 60 * 1000L;      // successful lookups: 6 hours
    private static final long NEGATIVE_TTL_MS = 10 * 60 * 1000L;       // failed lookups: 10 minutes

    // Mojang usernames: 1-16 chars, letters/digits/underscore only.
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    // Mojang UUIDs: 32 hex chars (undashed) or the standard dashed form.
    private static final Pattern UUID_PATTERN =
        Pattern.compile("^[0-9a-fA-F]{32}$|^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    // Dedicated bounded pool for blocking HTTP calls so we never starve the
    // shared common ForkJoinPool (used by the server for parallel streams etc.).
    private static final Executor SKIN_EXECUTOR = Executors.newFixedThreadPool(4, new ThreadFactory() {
        private final AtomicInteger counter = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "combat-persistence-skin-fetch-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    });

    /** Copy of {@code base} with its textures property replaced. */
    public static GameProfile withTextures(GameProfile base, Property texture) {
        Multimap<String, Property> properties = HashMultimap.create();
        for (Map.Entry<String, Property> entry : base.properties().entries()) {
            properties.put(entry.getKey(), entry.getValue());
        }
        properties.removeAll("textures");
        properties.put("textures", texture);
        return new GameProfile(base.id(), base.name(), new PropertyMap(properties));
    }

    /**
     * Fetches skin properties asynchronously from the Mojang API.
     * This bypasses the need for the server to be in online mode.
     * The returned future completes with null when no skin could be resolved.
     */
    public static CompletableFuture<Property> fetchSkin(String identifier) {
        if (identifier == null) {
            return CompletableFuture.completedFuture(null);
        }
        CacheEntry cached = skinCache.get(identifier);
        if (cached != null) {
            long ttl = cached.property() != null ? CACHE_TTL_MS : NEGATIVE_TTL_MS;
            if (System.currentTimeMillis() - cached.fetchedAt() < ttl) {
                return CompletableFuture.completedFuture(cached.property());
            }
            skinCache.remove(identifier, cached);
        }

        // Reject anything that isn't a plain Mojang username or UUID before it ever
        // reaches a URL. This prevents URL/path injection and SSRF via crafted input.
        boolean looksLikeUuid = UUID_PATTERN.matcher(identifier).matches();
        boolean looksLikeName = USERNAME_PATTERN.matcher(identifier).matches();
        if (!looksLikeUuid && !looksLikeName) {
            Combatpersistence.LOGGER.warn("Rejected skin lookup for invalid identifier: {}", identifier);
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() -> {
            Property property = lookupSkin(identifier, looksLikeName && !looksLikeUuid);
            // Cache failures too (negative TTL) so a misspelled name can't be
            // spammed into Mojang API rate-limit exhaustion.
            skinCache.put(identifier, new CacheEntry(property, System.currentTimeMillis()));
            return property;
        }, SKIN_EXECUTOR);
    }

    private static Property lookupSkin(String identifier, boolean resolveNameFirst) {
        try {
            String uuid = identifier;

            // 1. If identifier is a name, we must get the UUID first
            if (resolveNameFirst) {
                JsonObject nameResp = getJson("https://api.mojang.com/users/profiles/minecraft/" + identifier, identifier);
                if (nameResp == null) {
                    return null; // Player not found or rate limited
                }
                uuid = nameResp.get("id").getAsString();
            }

            // The UUID returned by the name lookup is attacker-influenced data;
            // re-validate it before building the second URL.
            if (!UUID_PATTERN.matcher(uuid).matches()) {
                Combatpersistence.LOGGER.warn("Rejected skin lookup: resolved UUID is not valid for {}", identifier);
                return null;
            }

            // 2. Fetch the signed profile from Mojang sessionserver
            JsonObject response = getJson("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid + "?unsigned=false", identifier);
            if (response != null && response.has("properties")) {
                JsonArray props = response.getAsJsonArray("properties");
                for (int i = 0; i < props.size(); i++) {
                    JsonObject prop = props.get(i).getAsJsonObject();
                    if (prop.get("name").getAsString().equals("textures")) {
                        String value = prop.get("value").getAsString();
                        String signature = prop.has("signature") ? prop.get("signature").getAsString() : "";
                        return new Property("textures", value, signature);
                    }
                }
            }
        } catch (Exception e) {
            Combatpersistence.LOGGER.error("Failed to fetch skin {} from Mojang API", identifier, e);
        }
        return null;
    }

    private static JsonObject getJson(String url, String identifier) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            int code = conn.getResponseCode();
            if (code == 200) {
                try (InputStreamReader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
                    return JsonParser.parseReader(reader).getAsJsonObject();
                }
            }
            if (code == 429) {
                Combatpersistence.LOGGER.warn("Mojang API Rate Limit hit while fetching skin for {}", identifier);
            }
            return null;
        } finally {
            conn.disconnect();
        }
    }

    public static void applySkin(ServerPlayer player, String skinName) {
        MinecraftServer server = player.level().getServer();

        fetchSkin(skinName).thenAccept(skinProperty -> {
            if (skinProperty != null) {
                server.execute(() -> {
                    if (player.isRemoved() || player.connection == null) return;

                    GameProfile newProfile = withTextures(player.getGameProfile(), skinProperty);

                    // Swap the profile through the accessor mixin — reflection by
                    // field name only worked in dev, where fields aren't remapped.
                    ((PlayerAccessor) player).combatpersistence$setGameProfile(newProfile);

                    // Refresh player for everyone
                    refreshPlayer(player);
                    Combatpersistence.LOGGER.info("Applied skin {} to player {}", skinName, player.getName().getString());
                });
            }
        });
    }

    private static void refreshPlayer(ServerPlayer player) {
        ServerLevel world = player.level();
        MinecraftServer server = world.getServer();

        // Refresh the tab-list entry (which carries the skin) for everyone.
        server.getPlayerList().broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(player.getUUID())));
        server.getPlayerList().broadcastAll(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(player)));

        // Re-spawn the entity ONLY for clients the entity tracker is actually
        // pairing with this player. Hand-sending spawn packets to everyone in the
        // dimension (as this used to do) created ghost copies on clients out of
        // tracking range — a frozen duplicate the tracker never moves or removes —
        // and could race the join-time pairing, leaving the real player invisible.
        // At join time nothing is tracking yet, so nothing is sent here and the
        // initial pairing already carries the updated profile.
        world.getChunkSource().sendToTrackingPlayers(player,
            new net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket(player.getId()));
        world.getChunkSource().sendToTrackingPlayers(player,
            new net.minecraft.network.protocol.game.ClientboundAddEntityPacket(
                player.getId(), player.getUUID(),
                player.getX(), player.getY(), player.getZ(),
                player.getXRot(), player.getYRot(),
                net.minecraft.world.entity.EntityTypes.PLAYER, 0,
                player.getDeltaMovement(), player.getYHeadRot()));

        List<net.minecraft.network.syncher.SynchedEntityData.DataValue<?>> entityData = player.getEntityData().getNonDefaultValues();
        if (entityData != null && !entityData.isEmpty()) {
            world.getChunkSource().sendToTrackingPlayers(player,
                new net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket(player.getId(), entityData));
        }

        // The freshly re-spawned entity has no equipment client-side; resend it.
        List<com.mojang.datafixers.util.Pair<net.minecraft.world.entity.EquipmentSlot, net.minecraft.world.item.ItemStack>> equipment = new java.util.ArrayList<>();
        for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
            net.minecraft.world.item.ItemStack stack = player.getItemBySlot(slot);
            if (!stack.isEmpty()) {
                equipment.add(com.mojang.datafixers.util.Pair.of(slot, stack.copy()));
            }
        }
        if (!equipment.isEmpty()) {
            world.getChunkSource().sendToTrackingPlayers(player,
                new net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket(player.getId(), equipment));
        }

        // The player's own client needs a more forceful update.
        // Send a respawn packet with KEEP_ALL_DATA to force entity recreation without losing state
        player.connection.send(new ClientboundRespawnPacket(player.createCommonSpawnInfo(world), (byte) 3));

        // Teleport them to their current position to trigger the camera/position sync
        TeleportTransition transition = new TeleportTransition(
            world,
            player.position(),
            player.getDeltaMovement(),
            player.getYRot(),
            player.getXRot(),
            TeleportTransition.DO_NOTHING
        );
        player.teleport(transition);

        // Refresh abilities and inventory sync
        player.onUpdateAbilities();
        server.getPlayerList().sendPlayerPermissionLevel(player);
        server.getPlayerList().sendLevelInfo(player, world);
        server.getPlayerList().sendAllPlayerInfo(player);
        player.containerMenu.sendAllDataToRemote();
    }
}
