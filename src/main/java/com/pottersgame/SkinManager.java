package com.pottersgame;

import com.google.common.collect.HashMultimap;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
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

    private static final Map<String, Property> skinCache = new ConcurrentHashMap<>();

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

    /**
     * Fetches skin properties asynchronously from the Mojang API.
     * This bypasses the need for the server to be in online mode.
     */
    public static CompletableFuture<Property> fetchSkin(String identifier) {
        if (identifier == null) {
            return CompletableFuture.completedFuture(null);
        }
        if (skinCache.containsKey(identifier)) {
            return CompletableFuture.completedFuture(skinCache.get(identifier));
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
            try {
                String uuid = identifier;

                // 1. If identifier is a name, we must get the UUID first
                if (looksLikeName && !looksLikeUuid) {
                    URL nameUrl = URI.create("https://api.mojang.com/users/profiles/minecraft/" + identifier).toURL();
                    HttpURLConnection nameConn = (HttpURLConnection) nameUrl.openConnection();
                    nameConn.setRequestMethod("GET");
                    nameConn.setConnectTimeout(5000);
                    nameConn.setReadTimeout(5000);
                    if (nameConn.getResponseCode() == 200) {
                        JsonObject nameResp = JsonParser.parseReader(new InputStreamReader(nameConn.getInputStream())).getAsJsonObject();
                        uuid = nameResp.get("id").getAsString();
                    } else {
                        return null; // Player not found or rate limited
                    }
                }

                // The UUID returned by the name lookup is attacker-influenced data;
                // re-validate it before building the second URL.
                if (!UUID_PATTERN.matcher(uuid).matches()) {
                    Combatpersistence.LOGGER.warn("Rejected skin lookup: resolved UUID is not valid for {}", identifier);
                    return null;
                }

                // 2. Fetch the signed profile from Mojang sessionserver
                URL url = URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid + "?unsigned=false").toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                if (conn.getResponseCode() == 200) {
                    JsonObject response = JsonParser.parseReader(new InputStreamReader(conn.getInputStream())).getAsJsonObject();
                    if (response.has("properties")) {
                        JsonArray props = response.getAsJsonArray("properties");
                        for (int i = 0; i < props.size(); i++) {
                            JsonObject prop = props.get(i).getAsJsonObject();
                            if (prop.get("name").getAsString().equals("textures")) {
                                String value = prop.get("value").getAsString();
                                String signature = prop.has("signature") ? prop.get("signature").getAsString() : "";
                                Property property = new Property("textures", value, signature);
                                skinCache.put(identifier, property);
                                return property;
                            }
                        }
                    }
                } else if (conn.getResponseCode() == 429) {
                    Combatpersistence.LOGGER.warn("Mojang API Rate Limit hit while fetching skin for {}", identifier);
                }
            } catch (Exception e) {
                Combatpersistence.LOGGER.error("Failed to fetch skin {} from Mojang API", identifier, e);
            }
            return null;
        }, SKIN_EXECUTOR);
    }

    private static MinecraftServer getServer(ServerPlayer player) {
        try {
            Field serverField = ServerPlayer.class.getDeclaredField("server");
            serverField.setAccessible(true);
            return (MinecraftServer) serverField.get(player);
        } catch (Exception e) {
            if (player.level() instanceof ServerLevel sl) {
                return sl.getServer();
            }
        }
        return null;
    }

    public static void applySkin(ServerPlayer player, String skinName) {
        MinecraftServer server = getServer(player);
        if (server == null) return;
        
        fetchSkin(skinName).thenAccept(skinProperty -> {
            if (skinProperty != null) {
                server.execute(() -> {
                    // Get the player's current GameProfile
                    GameProfile oldProfile = player.getGameProfile();
                    
                    // Create a new mutable Multimap
                    com.google.common.collect.Multimap<String, Property> properties = HashMultimap.create();
                    
                    // Copy existing properties from the old GameProfile
                    for (Map.Entry<String, Property> entry : oldProfile.properties().entries()) {
                        properties.put(entry.getKey(), entry.getValue());
                    }
                    
                    // Remove existing "textures" properties and add the new one
                    properties.removeAll("textures");
                    properties.put("textures", skinProperty);
                    
                    // Wrap the final multimap in an immutable PropertyMap
                    PropertyMap updatedProperties = new PropertyMap(properties);
                    
                    // Create a new GameProfile record
                    GameProfile newProfile = new GameProfile(oldProfile.id(), oldProfile.name(), updatedProperties);
                    
                    // Replace the GameProfile in the Player object using reflection
                    try {
                        Field profileField = Player.class.getDeclaredField("gameProfile");
                        profileField.setAccessible(true);
                        profileField.set(player, newProfile);
                    } catch (Exception e) {
                        Combatpersistence.LOGGER.error("Failed to swap GameProfile via reflection", e);
                    }
                    
                    // Refresh player for everyone
                    refreshPlayer(player);
                    Combatpersistence.LOGGER.info("Applied skin {} to player {}", skinName, player.getName().getString());
                });
            }
        });
    }

    private static void refreshPlayer(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel world)) return;
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
