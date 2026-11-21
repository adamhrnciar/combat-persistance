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
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
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
    /** NPC UUIDs this mod put on a scoreboard team; swept at startup. */
    private final Set<UUID> npcTeamEntries = Collections.newSetFromMap(new ConcurrentHashMap<>());

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

    /** Saves a player's data file; a seam so tests can inject write failures. */
    @FunctionalInterface
    interface PlayerSave {
        void save(ServerPlayer player);
    }

    enum KeptOutcome { RESTORED, ALREADY_PLACED, NO_ROOM, REFUSED }

    /** Result of one batch restore; {@code missingSlots} is set for {@link KeptOutcome#NO_ROOM}. */
    record KeptRestore(KeptOutcome outcome, int missingSlots) {
        static final KeptRestore REFUSED = new KeptRestore(KeptOutcome.REFUSED, 0);
    }

    /** Target slot per item (all empty and distinct), or how many more free slots are needed. */
    record KeptPlacement(int[] slots, int missingSlots) {}

    /**
     * Plans a batch into empty slots only, so a placement can always be undone
     * exactly. Items go back to their original slot when it is empty, the rest
     * into free main-inventory slots. A batch is placed whole or not at all.
     */
    static KeptPlacement planKeptPlacement(net.minecraft.world.entity.player.Inventory inventory,
            List<SlottedInventory.SlottedStack> items) {
        int size = inventory.getContainerSize();
        boolean[] taken = new boolean[size];
        int[] slots = new int[items.size()];
        Arrays.fill(slots, -1);
        for (int i = 0; i < items.size(); i++) {
            int slot = items.get(i).slot();
            if (slot >= 0 && slot < size && !taken[slot] && inventory.getItem(slot).isEmpty()) {
                slots[i] = slot;
                taken[slot] = true;
            }
        }
        int missing = 0;
        int next = 0;
        for (int i = 0; i < items.size(); i++) {
            if (slots[i] >= 0) continue;
            while (next < SlottedInventory.MAIN_SIZE && (taken[next] || !inventory.getItem(next).isEmpty())) next++;
            if (next < SlottedInventory.MAIN_SIZE) {
                slots[i] = next;
                taken[next] = true;
            } else {
                missing++;
            }
        }
        return new KeptPlacement(slots, missing);
    }

    /**
     * Hands kept items back once the owner is alive again after their
     * join-death. No-op while dead or still owing a death. Called on join,
     * authentication and respawn, so a batch that does not fit yet is simply
     * retried on the next of those.
     */
    public void restoreKeptItems(ServerPlayer player) {
        UUID uuid = player.getUUID();
        if (!player.isAlive() || isPendingDeath(uuid)) return;
        try {
            List<KeptBatch> batches = getKeptBatches(uuid);
            removeStaleKeptMarkers(player, batches);
            boolean restored = false;
            for (KeptBatch batch : batches) {
                KeptRestore result = restoreKeptBatch(player, batch, () -> decodeKept(player, batch), PlayerDataPersistence::save);
                if (result.outcome() == KeptOutcome.RESTORED) {
                    restored = true;
                } else if (result.outcome() == KeptOutcome.NO_ROOM) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal(String.format(
                        Combatpersistence.config.keptItemsNeedSpaceMessage, result.missingSlots())));
                    break;
                }
            }
            if (restored) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    Combatpersistence.config.keptItemsRestoredMessage));
            }
        } catch (RuntimeException error) {
            // Never block a join or respawn; the durable batch stays for a retry.
            Combatpersistence.LOGGER.error("Could not return kept items to {}", player.getName().getString(), error);
        }
    }

    private static List<SlottedInventory.SlottedStack> decodeKept(ServerPlayer player, KeptBatch batch) {
        SlottedInventory.DecodeResult decoded = SlottedInventory.decodeAll(player.level(), batch.items);
        return decoded.failures() == 0 ? decoded.items() : null;
    }

    /** Drops markers whose batch is no longer pending (consumed after a failed tag removal). */
    static void removeStaleKeptMarkers(ServerPlayer player, List<KeptBatch> pending) {
        Set<String> live = new HashSet<>();
        for (KeptBatch batch : pending) live.add(KEPT_MARKER_PREFIX + batch.batchId);
        for (String tag : List.copyOf(player.entityTags())) {
            if (tag.startsWith(KEPT_MARKER_PREFIX) && !live.contains(tag)) player.removeTag(tag);
        }
    }

    /**
     * Places one kept batch at most once. The marker tag goes on the player
     * before any item does, and player data with items and marker is saved
     * before the batch is consumed. A present marker means the batch is
     * already in the player's data, so it is only consumed. If anything fails
     * before that save completes, the placement and marker are undone and the
     * batch stays pending. Nothing is ever dropped on the ground.
     */
    KeptRestore restoreKeptBatch(ServerPlayer player, KeptBatch batch,
            java.util.function.Supplier<List<SlottedInventory.SlottedStack>> decode, PlayerSave save) {
        UUID uuid = player.getUUID();
        String name = player.getName().getString();
        String marker = KEPT_MARKER_PREFIX + batch.batchId;
        boolean alreadyPlaced = player.entityTags().contains(marker);
        if (!alreadyPlaced) {
            List<SlottedInventory.SlottedStack> items = decode.get();
            if (items == null) {
                Combatpersistence.LOGGER.error("Kept combat-log items for {} cannot be decoded; batch {} retained for staff repair",
                    name, batch.batchId);
                return KeptRestore.REFUSED;
            }
            net.minecraft.world.entity.player.Inventory inventory = player.getInventory();
            KeptPlacement plan = planKeptPlacement(inventory, items);
            if (plan.missingSlots() > 0) {
                Combatpersistence.debug("Kept batch {} for {} needs {} more free slots; left pending", batch.batchId, name, plan.missingSlots());
                return new KeptRestore(KeptOutcome.NO_ROOM, plan.missingSlots());
            }
            if (!player.addTag(marker)) {
                // Vanilla caps entity tags at 1024. Without the marker the
                // placement could not be made idempotent, so do not place.
                Combatpersistence.LOGGER.error("Could not tag {} for kept batch {} (tag limit reached?); batch retained", name, batch.batchId);
                return KeptRestore.REFUSED;
            }
            List<Integer> placed = new ArrayList<>();
            try {
                for (int i = 0; i < items.size(); i++) {
                    inventory.setItem(plan.slots()[i], items.get(i).stack());
                    placed.add(plan.slots()[i]);
                }
                save.save(player);
            } catch (RuntimeException error) {
                try {
                    for (int slot : placed) inventory.setItem(slot, ItemStack.EMPTY);
                    player.removeTag(marker);
                } catch (RuntimeException undoError) {
                    // Keep the marker: a later consume without placement is
                    // safer than placing this batch a second time.
                    Combatpersistence.LOGGER.error("Could not undo kept batch {} for {}; marker kept", batch.batchId, name, undoError);
                }
                Combatpersistence.LOGGER.error("Could not commit kept items for {}; batch {} retained", name, batch.batchId, error);
                return KeptRestore.REFUSED;
            }
            Combatpersistence.debug("Returned {} kept items to {}", items.size(), name);
        }
        try {
            removeKeptBatch(uuid, batch.batchId);
            player.removeTag(marker);
        } catch (RuntimeException error) {
            // The marker in player.dat prevents a second placement on retry.
            Combatpersistence.LOGGER.error("Could not consume kept batch {} for {}; it will be cleared on the next restore",
                batch.batchId, name, error);
        }
        return new KeptRestore(alreadyPlaced ? KeptOutcome.ALREADY_PLACED : KeptOutcome.RESTORED, 0);
    }

    // ---------------------------------------------------------------- NPC team entries

    /** Records an NPC's scoreboard team entry before it is added, so it can always be cleaned up. */
    public void recordNpcTeamEntry(UUID npcUuid) {
        if (!npcTeamEntries.add(npcUuid)) return;
        try {
            saveData(false);
        } catch (RuntimeException error) {
            npcTeamEntries.remove(npcUuid);
            throw error;
        }
    }

    /** Removes an NPC's team entry (loaded or not) and forgets it. */
    public void releaseNpcTeam(Scoreboard scoreboard, UUID npcUuid) {
        String entry = npcUuid.toString();
        PlayerTeam team = scoreboard.getPlayersTeam(entry);
        if (team != null) scoreboard.removePlayerFromTeam(entry, team);
        if (npcTeamEntries.remove(npcUuid)) saveData(true);
    }

    /**
     * Startup cleanup. NPCs are never saved, and chunk unload or server stop
     * can skip the removal hook, so recorded team entries can outlive them.
     * Only UUIDs this mod recorded are touched: real player names and other
     * entities that operators put on teams are never removed.
     *
     * @return how many stale entries were removed from a team
     */
    public int sweepNpcTeamEntries(Scoreboard scoreboard, java.util.function.Predicate<UUID> isLoaded) {
        int removed = 0;
        boolean changed = false;
        for (UUID npcUuid : List.copyOf(npcTeamEntries)) {
            if (isLoaded.test(npcUuid)) continue;
            String entry = npcUuid.toString();
            PlayerTeam team = scoreboard.getPlayersTeam(entry);
            if (team != null) {
                scoreboard.removePlayerFromTeam(entry, team);
                removed++;
            }
            npcTeamEntries.remove(npcUuid);
            changed = true;
        }
        if (changed) {
            try {
                saveData(false);
            } catch (RuntimeException error) {
                // Only bookkeeping: an entry kept on disk is swept again next start.
                Combatpersistence.LOGGER.error("Could not save the NPC team sweep", error);
            }
        }
        return removed;
    }

    Set<UUID> getNpcTeamEntries() {
        return Collections.unmodifiableSet(npcTeamEntries);
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
        root.add("npcTeamEntries", GSON.toJsonTree(npcTeamEntries));
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
                if (root.has("npcTeamEntries")) {
                    Type setType = new TypeToken<HashSet<UUID>>(){}.getType();
                    Set<UUID> loaded = GSON.fromJson(root.get("npcTeamEntries"), setType);
                    if (loaded == null || loaded.contains(null)) throw new IllegalStateException("Invalid NPC team data");
                    npcTeamEntries.addAll(loaded);
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
                    releaseNpcTeam(server.getScoreboard(), npcData.npcUuid);
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
