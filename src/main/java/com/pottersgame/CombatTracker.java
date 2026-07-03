package com.pottersgame;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtAccounter;
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
        public List<String> inventoryNbt; // Base64 encoded NBT

        public ActiveNPCData(UUID playerUuid, UUID npcUuid, List<ItemStack> inventory, ServerLevel world) {
            this.playerUuid = playerUuid;
            this.npcUuid = npcUuid;
            this.inventoryNbt = new ArrayList<>();
            for (ItemStack stack : inventory) {
                if (!stack.isEmpty()) {
                    try {
                        CompoundTag tag = (CompoundTag) ItemStack.CODEC.encodeStart(world.registryAccess().createSerializationContext(NbtOps.INSTANCE), stack)
                            .getOrThrow(e -> new RuntimeException("Failed to encode item: " + e));
                        
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        NbtIo.writeCompressed(tag, baos);
                        this.inventoryNbt.add(Base64.getEncoder().encodeToString(baos.toByteArray()));
                    } catch (Exception e) {
                        Combatpersistence.LOGGER.error("Failed to encode item to Base64 NBT", e);
                    }
                }
            }
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

    public void addNPC(UUID playerUuid, UUID npcUuid, List<ItemStack> inventory, ServerLevel world) {
        activeNPCs.put(playerUuid, new ActiveNPCData(playerUuid, npcUuid, inventory, world));
        // Synchronous: the caller clears the player's live inventory right after
        // this, and vanilla then writes the emptied player.dat. If the server dies
        // before this snapshot reaches the disk, the items exist nowhere.
        saveData(false);
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
                for (ItemStack stack : inventory) {
                    entity.spawnAtLocation((ServerLevel) entity.level(), stack);
                }
                // The death mixin injects at HEAD of LivingEntity.die — BEFORE
                // vanilla's re-entry guard — so die() can run through here twice
                // (e.g. /kill on a dying entity). Clear the list so a second pass
                // can't drop the whole inventory again.
                inventory.clear();
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
            for (String b64 : data.inventoryNbt) {
                try {
                    byte[] bytes = Base64.getDecoder().decode(b64);
                    CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
                    ItemStack.CODEC.parse(world.registryAccess().createSerializationContext(NbtOps.INSTANCE), tag)
                        .resultOrPartial(e -> Combatpersistence.LOGGER.error("Failed to decode item: {}", e))
                        .ifPresent(stack -> {
                             entity.spawnAtLocation(world, stack);
                        });
                } catch (Exception e) {
                    Combatpersistence.LOGGER.error("Failed to parse NPC inventory item from Base64", e);
                }
            }
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
                    if (loaded != null) activeNPCs.putAll(loaded);
                }
            } catch (Exception e) {
                Combatpersistence.LOGGER.error("Failed to load combat data", e);
            }
        }
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
                }
                it.remove();
            }
        }
    }

    public void restoreInventory(ServerPlayer player) {
        ActiveNPCData data = activeNPCs.get(player.getUUID());
        if (data == null) return;

        ServerLevel world = player.level();
        for (String b64 : data.inventoryNbt) {
            try {
                byte[] bytes = Base64.getDecoder().decode(b64);
                CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
                ItemStack.CODEC.parse(world.registryAccess().createSerializationContext(NbtOps.INSTANCE), tag)
                    .resultOrPartial(e -> Combatpersistence.LOGGER.error("Failed to decode restored item: {}", e))
                    .ifPresent(stack -> {
                         if (!player.getInventory().add(stack)) {
                             player.drop(stack, false);
                         }
                    });
            } catch (Exception e) {
                Combatpersistence.LOGGER.error("Failed to parse restored inventory item from Base64", e);
            }
        }
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(Combatpersistence.config.inventoryRestoredMessage));

        // Only drop the tracker entry once the items are back in the live
        // inventory, and persist that removal synchronously — same prefer-no-loss
        // ordering as the pre-auth stash. Removing first meant a crash mid-restore
        // destroyed the only durable copy of the items.
        activeNPCs.remove(player.getUUID());
        saveData(false);
    }
}
