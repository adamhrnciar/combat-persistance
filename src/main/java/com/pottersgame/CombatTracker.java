package com.pottersgame;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import com.pottersgame.api.CombatNpcEvents;

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
    /** Items a {@link CombatNpcEvents#KEEP_ON_NPC_DEATH} listener kept when the
     *  owner's NPC died. Written in the same synchronous save as the death
     *  tombstone and handed back after the owner's join-death and respawn. */
    private final Map<UUID, List<KeptBatch>> keptItems = new ConcurrentHashMap<>();
    /** Entity tag written into player.dat together with a restored kept batch,
     *  so a crash between the player save and the tracker save cannot place the
     *  same batch twice. */
    static final String KEPT_MARKER_PREFIX = "combatpersistence.kept.";

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

    /** One NPC death's worth of kept items, identified so its restore is idempotent. */
    public static class KeptBatch {
        public UUID batchId;
        public List<SlottedInventory.SlotItem> items;

        public KeptBatch() {}

        public KeptBatch(UUID batchId, List<SlottedInventory.SlotItem> items) {
            this.batchId = batchId;
            this.items = items;
        }
    }

    /** Outcome of asking {@link CombatNpcEvents#KEEP_ON_NPC_DEATH} about each stored item. */
    record KeepSplit(List<SlottedInventory.SlotItem> kept, List<SlottedInventory.SlottedStack> dropped) {}

    /**
     * Splits a fully decoded NPC inventory into kept and dropped items.
     * {@code decoded} must be the complete decode of {@code encoded} (same
     * order, no failures), so kept items reuse their original encoding and
     * cannot fail to persist. Listeners see a copy of each stack.
     */
    static KeepSplit splitForKeep(UUID owner, List<SlottedInventory.SlotItem> encoded,
            List<SlottedInventory.SlottedStack> decoded, CombatNpcEvents.KeepOnNpcDeath keep) {
        if (encoded.size() != decoded.size()) throw new IllegalArgumentException("Incomplete inventory decode");
        List<SlottedInventory.SlotItem> kept = new ArrayList<>();
        List<SlottedInventory.SlottedStack> dropped = new ArrayList<>();
        for (int i = 0; i < decoded.size(); i++) {
            SlottedInventory.SlottedStack item = decoded.get(i);
            boolean keepIt;
            try {
                keepIt = keep.shouldKeep(owner, item.stack().copy(), item.slot(),
                    item.slot() >= SlottedInventory.MAIN_SIZE);
            } catch (RuntimeException error) {
                Combatpersistence.LOGGER.error("Keep-item decision failed for {}; dropping the item as usual", owner, error);
                keepIt = false;
            }
            if (keepIt) kept.add(encoded.get(i));
            else dropped.add(item);
        }
        return new KeepSplit(kept, dropped);
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
        markOfflineDeath(playerUuid, List.of());
    }

    /**
     * Records the owner's combat-log death and, in the same synchronous save,
     * any items kept for them. Nothing may drop before this returns.
     */
    public void markOfflineDeath(UUID playerUuid, List<SlottedInventory.SlotItem> kept) {
        boolean addedDeath = offlineDeaths.add(playerUuid);
        boolean addedPending = pendingJoinDeaths.add(playerUuid);
        ActiveNPCData removed = activeNPCs.remove(playerUuid);
        KeptBatch batch = kept.isEmpty() ? null : new KeptBatch(UUID.randomUUID(), new ArrayList<>(kept));
        if (batch != null) keptItems.computeIfAbsent(playerUuid, ignored -> new ArrayList<>()).add(batch);
        try {
            // A durable tombstone (and the kept items) must exist before any item becomes loot.
            saveData(false);
        } catch (RuntimeException error) {
            if (addedDeath) offlineDeaths.remove(playerUuid);
            if (addedPending) pendingJoinDeaths.remove(playerUuid);
            if (removed != null) activeNPCs.put(playerUuid, removed);
            if (batch != null) removeBatchInMemory(playerUuid, batch.batchId);
            throw error;
        }
        Combatpersistence.debug("Marked offline combat-log death for {} (tracker entry removed, {} items kept; they die on next join)",
            playerUuid, kept.size());
    }

    /** The kept-item batches waiting for this owner (a copy; empty when none). */
    public List<KeptBatch> getKeptBatches(UUID playerUuid) {
        List<KeptBatch> batches = keptItems.get(playerUuid);
        return batches == null ? List.of() : List.copyOf(batches);
    }

    /** Consumes one kept batch with a synchronous save; restores it in memory if the write fails. */
    public void removeKeptBatch(UUID playerUuid, UUID batchId) {
        KeptBatch removed = removeBatchInMemory(playerUuid, batchId);
        if (removed == null) return;
        try {
            saveData(false);
        } catch (RuntimeException error) {
            keptItems.computeIfAbsent(playerUuid, ignored -> new ArrayList<>()).add(removed);
            throw error;
        }
    }

    private KeptBatch removeBatchInMemory(UUID playerUuid, UUID batchId) {
        List<KeptBatch> batches = keptItems.get(playerUuid);
        if (batches == null) return null;
        KeptBatch removed = null;
        for (Iterator<KeptBatch> it = batches.iterator(); it.hasNext(); ) {
            KeptBatch batch = it.next();
            if (batch.batchId.equals(batchId)) {
                removed = batch;
                it.remove();
                break;
            }
        }
        if (batches.isEmpty()) keptItems.remove(playerUuid);
        return removed;
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
        handleEntityDeath(entity, null);
    }

    public void handleEntityDeath(Entity entity, DamageSource source) {
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
                dropNpcInventory(entity, data, source);
                return;
            }
        }
    }

    private void dropNpcInventory(Entity entity, ActiveNPCData data, DamageSource source) {
        if (entity.level() instanceof ServerLevel world) {
            SlottedInventory.DecodeResult decoded = SlottedInventory.decodeAll(world, data.items);
            if (decoded.failures() != 0) {
                Combatpersistence.LOGGER.error("NPC inventory cannot be decoded; keeping its recovery snapshot for {}", data.playerUuid);
                return;
            }
            KeepSplit split = splitForKeep(data.playerUuid, data.items, decoded.items(),
                CombatNpcEvents.KEEP_ON_NPC_DEATH.invoker());
            try {
                // The death mixin runs before vanilla's re-entry guard. Remove
                // the authoritative entry synchronously before exposing loot so
                // a second die() call or crash cannot restore the same items.
                // Kept items are persisted in this same save.
                markOfflineDeath(data.playerUuid, split.kept());
            } catch (RuntimeException error) {
                Combatpersistence.LOGGER.error("Could not commit NPC death; withholding loot for {}", data.playerUuid, error);
                return;
            }
            if (entity instanceof CombatNPC npc) npc.getStoredInventory().clear();
            for (SlottedInventory.SlottedStack item : split.dropped()) entity.spawnAtLocation(world, item.stack());
            Combatpersistence.debug("Dropped {} tracker-stored items for {} at {}, kept {} for the owner",
                split.dropped().size(), data.playerUuid, entity.blockPosition(), split.kept().size());

            if (entity instanceof LivingEntity living) {
                String ownerName = entity instanceof CombatNPC npc && npc.originalPlayerName != null
                    ? npc.originalPlayerName : data.playerUuid.toString();
                DamageSource cause = source != null ? source : world.damageSources().generic();
                try {
                    CombatNpcEvents.NPC_KILLED.invoker().onNpcKilled(world, data.playerUuid, ownerName, living, cause);
                } catch (RuntimeException error) {
                    Combatpersistence.LOGGER.error("Combat NPC kill listeners failed for {}", data.playerUuid, error);
                }
            }
        }
    }

    /**
     * Hands kept items back once the owner is alive again after their
     * join-death. No-op while dead or still owing a death. Each batch is placed
     * at most once: the player save that carries its items also carries a
     * marker tag, and only then is the batch consumed.
     */
    public void restoreKeptItems(ServerPlayer player) {
        UUID uuid = player.getUUID();
        if (!player.isAlive() || isPendingDeath(uuid)) return;
        try {
            for (KeptBatch batch : getKeptBatches(uuid)) {
                if (!restoreKeptBatch(player, batch)) return;
            }
        } catch (RuntimeException error) {
            // Never block a join or respawn; the durable batch stays for a retry.
            Combatpersistence.LOGGER.error("Could not return kept items to {}", player.getName().getString(), error);
        }
    }

    private boolean restoreKeptBatch(ServerPlayer player, KeptBatch batch) {
        UUID uuid = player.getUUID();
        String marker = KEPT_MARKER_PREFIX + batch.batchId;
        SlottedInventory.PlaceResult result = null;
        if (!player.entityTags().contains(marker)) {
            SlottedInventory.DecodeResult decoded = SlottedInventory.decodeAll(player.level(), batch.items);
            if (decoded.failures() != 0) {
                Combatpersistence.LOGGER.error("Kept combat-log items for {} cannot be decoded; batch {} retained for staff repair",
                    player.getName().getString(), batch.batchId);
                return false;
            }
            result = SlottedInventory.place(player, decoded.items(), false);
            player.addTag(marker);
            try {
                PlayerDataPersistence.save(player);
            } catch (RuntimeException error) {
                // The marker stays in memory, so this session never places the
                // batch twice; vanilla's next save writes items and marker together.
                // Withhold overflow: dropping it could duplicate after a crash.
                Combatpersistence.LOGGER.error("Could not commit kept items for {}; batch {} retained",
                    player.getName().getString(), batch.batchId, error);
                return false;
            }
            Combatpersistence.debug("Returned {} kept items to {} ({} exact slot, {} loose, {} overflow)",
                decoded.items().size(), player.getName().getString(), result.exact(), result.loose(), result.dropped());
        }
        try {
            removeKeptBatch(uuid, batch.batchId);
            player.removeTag(marker);
        } catch (RuntimeException error) {
            // The marker in player.dat prevents a second placement on retry.
            Combatpersistence.LOGGER.error("Could not consume kept batch {} for {}; it will be cleared on the next restore",
                batch.batchId, player.getName().getString(), error);
        }
        // Overflow is not part of player.dat; release it exactly once, now.
        if (result != null) {
            result.dropOverflow(player);
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                Combatpersistence.config.keptItemsRestoredMessage));
        }
        return true;
    }

    private void saveData() {
        saveData(true);
    }

    public void saveData(boolean async) {
        JsonObject root = new JsonObject();
        root.add("offlineDeaths", GSON.toJsonTree(offlineDeaths));
        root.add("pendingJoinDeaths", GSON.toJsonTree(pendingJoinDeaths));
        root.add("activeNPCs", GSON.toJsonTree(activeNPCs));
        root.add("keptItems", GSON.toJsonTree(keptItems));
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
                if (root.has("keptItems")) {
                    Type keptType = new TypeToken<HashMap<UUID, ArrayList<KeptBatch>>>(){}.getType();
                    Map<UUID, List<KeptBatch>> loaded = GSON.fromJson(root.get("keptItems"), keptType);
                    if (loaded == null) throw new IllegalStateException("Invalid kept item data");
                    Set<UUID> batchIds = new HashSet<>();
                    loaded.forEach((uuid, batches) -> {
                        if (uuid == null || batches == null || batches.isEmpty()) {
                            throw new IllegalStateException("Invalid kept item owner");
                        }
                        for (KeptBatch batch : batches) {
                            if (batch == null || batch.batchId == null || !batchIds.add(batch.batchId)
                                    || batch.items == null || batch.items.isEmpty()) {
                                throw new IllegalStateException("Invalid kept item batch");
                            }
                            for (SlottedInventory.SlotItem item : batch.items) {
                                if (item == null || item.nbt == null) throw new IllegalStateException("Invalid kept item");
                            }
                        }
                        keptItems.put(uuid, new ArrayList<>(batches));
                    });
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
                    CombatNPC.leaveTeam(server.getScoreboard(), npcData.npcUuid);
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
