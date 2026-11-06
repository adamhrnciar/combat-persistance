package com.pottersgame;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.io.*;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CombatTracker {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final File DATA_FILE;

    private final Map<UUID, Long> combatEndTimes = new ConcurrentHashMap<>();
    private final Map<UUID, ActiveNPCData> activeNPCs = new ConcurrentHashMap<>();
    private Set<UUID> offlineDeaths = new HashSet<>();
    private final Set<UUID> pendingJoinDeaths = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public CombatTracker() {
        this(FabricLoader.getInstance().getConfigDir());
    }

    CombatTracker(java.nio.file.Path directory) {
        DATA_FILE = directory.resolve("combat_persistence_data.json").toFile();
        loadData();
    }

    public static class ActiveNPCData {
        public UUID playerUuid;
        public UUID npcUuid;
        /** Items with the container slot they came from (NO_SLOT for cursor/crafting). */
        public List<SlottedInventory.SlotItem> items;
        /** Legacy pre-slot format (flat Base64 list). Only ever non-null right
         *  after gson loads an old data file; loadData() folds it into
         *  {@link #items} and nulls it, so the next save writes the new format. */
        public List<String> inventoryNbt;

        public ActiveNPCData(UUID playerUuid, UUID npcUuid, List<SlottedInventory.SlottedStack> inventory, ServerLevel world) {
            this.playerUuid = playerUuid;
            this.npcUuid = npcUuid;
            this.items = SlottedInventory.encodeAll(world, inventory);
        }
    }

    public void tag(ServerPlayer player, int durationSeconds) {
        combatEndTimes.put(player.getUUID(), System.currentTimeMillis() + (durationSeconds * 1000L));
    }

    public boolean isInCombat(ServerPlayer player) {
        Long endTime = combatEndTimes.get(player.getUUID());
        return endTime != null && System.currentTimeMillis() < endTime;
    }

    public Map<UUID, Long> getTaggedPlayers() {
        return Collections.unmodifiableMap(combatEndTimes);
    }

    public void addNPC(UUID playerUuid, UUID npcUuid, List<SlottedInventory.SlottedStack> inventory, ServerLevel world) {
        ActiveNPCData data = new ActiveNPCData(playerUuid, npcUuid, inventory, world);
        if (activeNPCs.putIfAbsent(playerUuid, data) != null) {
            throw new IllegalStateException("An inventory snapshot already exists for this player");
        }
        // Synchronous: the caller clears the player's live inventory right after
        // this, and vanilla then writes the emptied player.dat. If the server dies
        // before this snapshot reaches the disk, the items exist nowhere.
        try {
            saveData(false);
        } catch (RuntimeException error) {
            activeNPCs.remove(playerUuid, data);
            throw error;
        }
        Combatpersistence.debug("Tracker entry for {} persisted synchronously ({} items, NPC {})",
            playerUuid, data.items.size(), npcUuid);
    }

    public UUID getNPCForPlayer(UUID playerUuid) {
        ActiveNPCData data = activeNPCs.get(playerUuid);
        return data != null ? data.npcUuid : null;
    }

    public boolean isNpcActive(UUID npcUuid) {
        for (ActiveNPCData data : activeNPCs.values()) {
            if (data.npcUuid.equals(npcUuid)) return true;
        }
        return false;
    }

    public void removeNPC(UUID playerUuid) {
        getAndRemoveNPCData(playerUuid);
    }

    public ActiveNPCData getAndRemoveNPCData(UUID playerUuid) {
        ActiveNPCData data = activeNPCs.remove(playerUuid);
        if (data != null) {
            try {
                saveData(false);
            } catch (RuntimeException error) {
                activeNPCs.put(playerUuid, data);
                throw error;
            }
        }
        return data;
    }

    public void markOfflineDeath(UUID playerUuid) {
        boolean addedDeath = offlineDeaths.add(playerUuid);
        boolean addedPending = pendingJoinDeaths.add(playerUuid);
        ActiveNPCData removed = activeNPCs.remove(playerUuid);
        try {
            // A durable tombstone must exist before any item becomes loot.
            saveData(false);
        } catch (RuntimeException error) {
            if (addedDeath) offlineDeaths.remove(playerUuid);
            if (addedPending) pendingJoinDeaths.remove(playerUuid);
            if (removed != null) activeNPCs.put(playerUuid, removed);
            throw error;
        }
        Combatpersistence.debug("Marked offline combat-log death for {} (tracker entry removed; they die on next join)", playerUuid);
    }

    public boolean checkAndClearOfflineDeath(UUID playerUuid) {
        if (offlineDeaths.remove(playerUuid)) {
            try {
                saveData(false);
            } catch (RuntimeException error) {
                offlineDeaths.add(playerUuid);
                throw error;
            }
            return true;
        }
        return false;
    }

    public boolean isPendingDeath(UUID uuid) {
        return pendingJoinDeaths.contains(uuid);
    }

    public void removePendingDeath(UUID uuid) {
        boolean hadOfflineDeath = offlineDeaths.remove(uuid);
        if (pendingJoinDeaths.remove(uuid) || hadOfflineDeath) {
            try {
                saveData(false);
            } catch (RuntimeException error) {
                pendingJoinDeaths.add(uuid);
                if (hadOfflineDeath) offlineDeaths.add(uuid);
                throw error;
            }
        }
    }

    public void handleEntityDeath(Entity entity) {
        UUID entityUuid = entity.getUUID();

        if (entity instanceof ServerPlayer player) {
            // A player's legitimate death ends their combat tag; without this, a
            // player who dies, respawns and logs out inside the leftover tag
            // window spawns a pointless NPC of their respawned self.
            combatEndTimes.remove(player.getUUID());
            return;
        }

        for (ActiveNPCData data : activeNPCs.values()) {
            if (data.npcUuid.equals(entityUuid)) {
                dropNpcInventory(entity, data);
                return;
            }
        }
    }

    private void dropNpcInventory(Entity entity, ActiveNPCData data) {
        if (entity.level() instanceof ServerLevel world) {
            SlottedInventory.DecodeResult decoded = SlottedInventory.decodeAll(world, data.items);
            if (decoded.failures() != 0) {
                Combatpersistence.LOGGER.error("NPC inventory cannot be decoded; keeping its recovery snapshot for {}", data.playerUuid);
                return;
            }
            try {
                // The death mixin runs before vanilla's re-entry guard. Remove
                // the authoritative entry synchronously before exposing loot so
                // a second die() call or crash cannot restore the same items.
                markOfflineDeath(data.playerUuid);
            } catch (RuntimeException error) {
                Combatpersistence.LOGGER.error("Could not commit NPC death; withholding loot for {}", data.playerUuid, error);
                return;
            }
            if (entity instanceof CombatNPC npc) npc.getStoredInventory().clear();
            for (SlottedInventory.SlottedStack item : decoded.items()) entity.spawnAtLocation(world, item.stack());
            Combatpersistence.debug("Dropped {} tracker-stored items for {} at {} (fallback drop path)",
                decoded.items().size(), data.playerUuid, entity.blockPosition());
        }
    }

    private void saveData() {
        saveData(true);
    }

    public void saveData(boolean async) {
        JsonObject root = new JsonObject();
        root.add("offlineDeaths", GSON.toJsonTree(offlineDeaths));
        root.add("pendingJoinDeaths", GSON.toJsonTree(pendingJoinDeaths));
        root.add("activeNPCs", GSON.toJsonTree(activeNPCs));
        Runnable saveAction = () -> DiskIO.writeJson(DATA_FILE, GSON, root);

        if (async) {
            DiskIO.async(saveAction);
        } else {
            DiskIO.sync(saveAction);
        }
    }

    public void stop() {
        saveData(false);
    }

    private void loadData() {
        if (DATA_FILE.exists()) {
            try (FileReader reader = new FileReader(DATA_FILE)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                if (!root.has("offlineDeaths") || !root.get("offlineDeaths").isJsonArray()
                        || !root.has("activeNPCs") || !root.get("activeNPCs").isJsonObject()) {
                    throw new IllegalStateException("Missing combat recovery collections");
                }
                if (root.has("offlineDeaths")) {
                    Type setType = new TypeToken<HashSet<UUID>>(){}.getType();
                    offlineDeaths = GSON.fromJson(root.get("offlineDeaths"), setType);
                    if (offlineDeaths == null || offlineDeaths.contains(null)) throw new IllegalStateException("Invalid offline death data");
                }
                if (root.has("pendingJoinDeaths")) {
                    Type setType = new TypeToken<HashSet<UUID>>(){}.getType();
                    Set<UUID> loaded = GSON.fromJson(root.get("pendingJoinDeaths"), setType);
                    if (loaded == null || loaded.contains(null)) throw new IllegalStateException("Invalid pending death data");
                    pendingJoinDeaths.addAll(loaded);
                }
                if (root.has("activeNPCs")) {
                    Type mapType = new TypeToken<ConcurrentHashMap<UUID, ActiveNPCData>>(){}.getType();
                    Map<UUID, ActiveNPCData> loaded = GSON.fromJson(root.get("activeNPCs"), mapType);
                    if (loaded == null) throw new IllegalStateException("Invalid combat inventory data");
                    Set<UUID> npcUuids = new HashSet<>();
                    loaded.forEach((uuid, data) -> {
                        if (uuid == null || data == null || !uuid.equals(data.playerUuid) || data.npcUuid == null
                                || !npcUuids.add(data.npcUuid) || offlineDeaths.contains(uuid) || pendingJoinDeaths.contains(uuid)) {
                            throw new IllegalStateException("Invalid combat inventory owner or NPC identifier");
                        }
                        normalizeLoadedEntry(data);
                    });
                    activeNPCs.putAll(loaded);
                }
                pendingJoinDeaths.addAll(offlineDeaths);
            } catch (Exception e) {
                throw new IllegalStateException("Refusing to start with unreadable combat recovery data", e);
            }
        }
    }

    /**
     * Folds a legacy flat-list entry (pre-slot data format) into the slotted
     * format in memory; the next save rewrites the file in the new format.
     * Legacy items carry NO_SLOT and restore through the generic add() path.
     * Runs during static init, before the config loads, so it logs directly
     * (a one-time migration is worth an unconditional INFO line).
     */
    private static void normalizeLoadedEntry(ActiveNPCData data) {
        if (data.items == null) {
            if (data.inventoryNbt == null) throw new IllegalStateException("Missing combat inventory snapshot");
            data.items = new ArrayList<>();
            for (String b64 : data.inventoryNbt) {
                data.items.add(new SlottedInventory.SlotItem(SlottedInventory.NO_SLOT, b64));
            }
            org.slf4j.LoggerFactory.getLogger("combatpersistence").info("Migrated legacy combat data entry for {} ({} items, restored slotless)",
                data.playerUuid, data.items.size());
        }
        for (SlottedInventory.SlotItem item : data.items) {
            if (item == null || item.nbt == null) throw new IllegalStateException("Invalid combat inventory item");
        }
        data.inventoryNbt = null;
    }

    public void tick(net.minecraft.server.MinecraftServer server) {
        long now = System.currentTimeMillis();

        for (Iterator<Map.Entry<UUID, Long>> it = combatEndTimes.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Long> entry = it.next();
            if (now >= entry.getValue()) {
                UUID playerUuid = entry.getKey();
                ActiveNPCData npcData = activeNPCs.get(playerUuid);
                if (npcData != null) {
                    for (ServerLevel level : server.getAllLevels()) {
                        Entity npc = level.getEntity(npcData.npcUuid);
                        if (npc != null) {
                            npc.discard();
                            break;
                        }
                    }
                    Combatpersistence.debug("Combat tag expired for {}; NPC {} discarded, tracker entry kept for rejoin restore",
                        playerUuid, npcData.npcUuid);
                }
                it.remove();
            }
        }
    }

    public void restoreInventory(ServerPlayer player) {
        ActiveNPCData data = activeNPCs.get(player.getUUID());
        if (data == null) {
            Combatpersistence.debug("No combat-log tracker entry for {}; nothing to restore", player.getName().getString());
            return;
        }

        ServerLevel world = player.level();
        SlottedInventory.DecodeResult decoded = SlottedInventory.decodeAll(world, data.items);

        // Never add a partial recovery snapshot on top of a stale player.dat.
        // Leave the durable backup untouched for staff repair on any failure.
        if (decoded.failures() != 0) {
            throw new IllegalStateException("Combat recovery inventory could not be decoded; snapshot retained");
        }

        SlottedInventory.PlaceResult result = SlottedInventory.place(player, decoded.items(), true);
        Combatpersistence.debug(
            "Restored combat-log inventory for {}: {} items ({} exact slot of which {} equipment, {} loose, {} dropped), clearedFirst={}",
            player.getName().getString(), decoded.items().size(),
            result.exact(), result.equipped(), result.loose(), result.dropped(), true);

        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(Combatpersistence.config.inventoryRestoredMessage));

        // Commit player.dat before consuming its recovery snapshot. Overflow is
        // withheld until consumption commits, so retries cannot duplicate loot.
        PlayerDataPersistence.save(player);
        activeNPCs.remove(player.getUUID());
        try {
            saveData(false);
        } catch (RuntimeException error) {
            activeNPCs.put(player.getUUID(), data);
            throw error;
        }
        result.dropOverflow(player);
    }
}
