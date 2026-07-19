package com.pottersgame;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import java.io.*;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CombatTracker {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File DATA_FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "combat_persistence_data.json");

    private final Map<UUID, Long> combatEndTimes = new ConcurrentHashMap<>();
    private final Map<UUID, ActiveNPCData> activeNPCs = new ConcurrentHashMap<>();
    private Set<UUID> offlineDeaths = new HashSet<>();
    private final Set<UUID> pendingJoinDeaths = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public CombatTracker() {
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
        activeNPCs.put(playerUuid, data);
        // Synchronous: the caller clears the player's live inventory right after
        // this, and vanilla then writes the emptied player.dat. If the server dies
        // before this snapshot reaches the disk, the items exist nowhere.
        saveData(false);
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
        activeNPCs.remove(playerUuid);
        saveData();
    }

    public ActiveNPCData getAndRemoveNPCData(UUID playerUuid) {
        ActiveNPCData data = activeNPCs.remove(playerUuid);
        if (data != null) {
            saveData();
        }
        return data;
    }

    public void markOfflineDeath(UUID playerUuid) {
        offlineDeaths.add(playerUuid);
        pendingJoinDeaths.add(playerUuid);
        activeNPCs.remove(playerUuid);
        saveData();
        Combatpersistence.debug("Marked offline combat-log death for {} (tracker entry removed; they die on next join)", playerUuid);
    }

    public boolean checkAndClearOfflineDeath(UUID playerUuid) {
        if (offlineDeaths.remove(playerUuid)) {
            saveData();
            return true;
        }
        return false;
    }

    public boolean isPendingDeath(UUID uuid) {
        return pendingJoinDeaths.contains(uuid);
    }

    public void removePendingDeath(UUID uuid) {
        if (pendingJoinDeaths.remove(uuid)) {
            saveData();
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

        if (entity instanceof CombatNPC npc) {
            List<ItemStack> inventory = npc.getStoredInventory();
            if (!inventory.isEmpty()) {
                int count = inventory.size();
                for (ItemStack stack : inventory) {
                    entity.spawnAtLocation((ServerLevel) entity.level(), stack);
                }
                // The death mixin injects at HEAD of LivingEntity.die — BEFORE
                // vanilla's re-entry guard — so die() can run through here twice
                // (e.g. /kill on a dying entity). Clear the list so a second pass
                // can't drop the whole inventory again.
                inventory.clear();
                Combatpersistence.debug("CombatNPC of {} died at {}: dropped {} stored items",
                    npc.originalPlayerName, entity.blockPosition(), count);
            } else {
                for (ActiveNPCData data : activeNPCs.values()) {
                    if (data.npcUuid.equals(entityUuid)) {
                        dropNpcInventory(entity, data);
                        break;
                    }
                }
            }
            
            if (npc.originalPlayerUuid != null) {
                markOfflineDeath(npc.originalPlayerUuid);
            }
            return;
        }

        for (ActiveNPCData data : activeNPCs.values()) {
            if (data.npcUuid.equals(entityUuid)) {
                dropNpcInventory(entity, data);
                markOfflineDeath(data.playerUuid);
                return;
            }
        }
    }

    private void dropNpcInventory(Entity entity, ActiveNPCData data) {
        if (entity.level() instanceof ServerLevel world) {
            int count = 0;
            for (SlottedInventory.SlotItem item : data.items) {
                ItemStack stack = SlottedInventory.decode(world, item.nbt);
                if (stack != null && !stack.isEmpty()) {
                    entity.spawnAtLocation(world, stack);
                    count++;
                }
            }
            Combatpersistence.debug("Dropped {} tracker-stored items for {} at {} (fallback drop path)",
                count, data.playerUuid, entity.blockPosition());
        }
    }

    private void saveData() {
        saveData(true);
    }

    public void saveData(boolean async) {
        Set<UUID> deathsCopy = new HashSet<>(offlineDeaths);
        Set<UUID> pendingCopy = new HashSet<>(pendingJoinDeaths);
        Map<UUID, ActiveNPCData> npcsCopy = new HashMap<>(activeNPCs);

        Runnable saveAction = () -> {
            try (FileWriter writer = new FileWriter(DATA_FILE)) {
                JsonObject root = new JsonObject();
                root.add("offlineDeaths", GSON.toJsonTree(deathsCopy));
                root.add("pendingJoinDeaths", GSON.toJsonTree(pendingCopy));
                root.add("activeNPCs", GSON.toJsonTree(npcsCopy));
                GSON.toJson(root, writer);
            } catch (IOException e) {
                Combatpersistence.LOGGER.error("Failed to save combat data", e);
            }
        };

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
                if (root.has("offlineDeaths")) {
                    Type setType = new TypeToken<HashSet<UUID>>(){}.getType();
                    offlineDeaths = GSON.fromJson(root.get("offlineDeaths"), setType);
                }
                if (root.has("pendingJoinDeaths")) {
                    Type setType = new TypeToken<HashSet<UUID>>(){}.getType();
                    Set<UUID> loaded = GSON.fromJson(root.get("pendingJoinDeaths"), setType);
                    if (loaded != null) pendingJoinDeaths.addAll(loaded);
                }
                if (root.has("activeNPCs")) {
                    Type mapType = new TypeToken<ConcurrentHashMap<UUID, ActiveNPCData>>(){}.getType();
                    Map<UUID, ActiveNPCData> loaded = GSON.fromJson(root.get("activeNPCs"), mapType);
                    if (loaded != null) {
                        loaded.values().forEach(CombatTracker::normalizeLoadedEntry);
                        activeNPCs.putAll(loaded);
                    }
                }
            } catch (Exception e) {
                Combatpersistence.LOGGER.error("Failed to load combat data", e);
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
            data.items = new ArrayList<>();
            if (data.inventoryNbt != null) {
                for (String b64 : data.inventoryNbt) {
                    data.items.add(new SlottedInventory.SlotItem(SlottedInventory.NO_SLOT, b64));
                }
                Combatpersistence.LOGGER.info("Migrated legacy combat data entry for {} ({} items, restored slotless)",
                    data.playerUuid, data.items.size());
            }
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

        // Full decode success makes this snapshot authoritative: wipe the live
        // inventory first (killing any stale player.dat copy left by an old
        // dupe or crash window) and put every item back in its exact slot. On
        // any decode failure keep whatever the player is holding and restore
        // additively instead — prefer a dupe over item loss.
        boolean clearFirst = decoded.failures() == 0;
        if (!clearFirst) {
            Combatpersistence.LOGGER.warn(
                "{} of {} stored items failed to decode for {} — restoring additively, live inventory NOT cleared (prefer dupe over loss)",
                decoded.failures(), data.items.size(), player.getName().getString());
        }

        SlottedInventory.PlaceResult result = SlottedInventory.place(player, decoded.items(), clearFirst);
        Combatpersistence.debug(
            "Restored combat-log inventory for {}: {} items ({} exact slot of which {} equipment, {} loose, {} dropped), clearedFirst={}",
            player.getName().getString(), decoded.items().size(),
            result.exact(), result.equipped(), result.loose(), result.dropped(), clearFirst);

        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(Combatpersistence.config.inventoryRestoredMessage));

        // Only drop the tracker entry once the items are back in the live
        // inventory, and persist that removal synchronously — same prefer-no-loss
        // ordering as the pre-auth stash. Removing first meant a crash mid-restore
        // destroyed the only durable copy of the items.
        activeNPCs.remove(player.getUUID());
        saveData(false);
    }
}
