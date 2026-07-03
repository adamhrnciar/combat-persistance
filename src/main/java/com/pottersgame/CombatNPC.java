package com.pottersgame;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class CombatNPC extends Mannequin {

    public UUID originalPlayerUuid;
    public String originalPlayerName;
    private boolean hasDropped = false;
    private final List<ItemStack> storedInventory = new ArrayList<>();

    public CombatNPC(EntityType<net.minecraft.world.entity.decoration.Mannequin> type, net.minecraft.world.level.Level world) {
        super(type, world);
    }

    public CombatNPC(ServerLevel world, UUID originalPlayerUuid, String originalPlayerName, List<ItemStack> inventory) {
        super(EntityTypes.MANNEQUIN, world);
        this.originalPlayerUuid = originalPlayerUuid;
        this.originalPlayerName = originalPlayerName;
        this.storedInventory.addAll(inventory);
    }

    @Override
    protected void addAdditionalSaveData(net.minecraft.world.level.storage.ValueOutput output) {
        super.addAdditionalSaveData(output);
        if (originalPlayerUuid != null) {
            output.store("OriginalPlayerUuid", net.minecraft.core.UUIDUtil.CODEC, originalPlayerUuid);
        }
        if (originalPlayerName != null) {
            output.putString("OriginalPlayerName", originalPlayerName);
        }
        output.putBoolean("HasDropped", hasDropped);

        if (!storedInventory.isEmpty()) {
            net.minecraft.world.level.storage.ValueOutput.TypedOutputList<ItemStack> invList = output.list("StoredInventory", ItemStack.CODEC);
            for (ItemStack stack : storedInventory) {
                invList.add(stack);
            }
        }
    }

    @Override
    protected void readAdditionalSaveData(net.minecraft.world.level.storage.ValueInput input) {
        super.readAdditionalSaveData(input);
        this.originalPlayerUuid = input.read("OriginalPlayerUuid", net.minecraft.core.UUIDUtil.CODEC).orElse(null);
        this.originalPlayerName = input.getString("OriginalPlayerName").orElse(null);
        this.hasDropped = input.getBooleanOr("HasDropped", false);

        this.storedInventory.clear();
        input.list("StoredInventory", ItemStack.CODEC).ifPresent(list -> {
            list.stream().forEach(this.storedInventory::add);
        });
    }

    public List<ItemStack> getStoredInventory() {
        return storedInventory;
    }

    public static CombatNPC spawn(ServerPlayer original, List<ItemStack> inventory) {
        ServerLevel world = (ServerLevel) original.level();
        MinecraftServer server = world.getServer();
        CombatConfig config = Combatpersistence.config;

        String playerName = original.getName().getString();
        CombatNPC npc = new CombatNPC(world, original.getUUID(), playerName, inventory);

        // Visual setup
        GameProfile currentProfile = original.getGameProfile();
        npc.setComponent(DataComponents.PROFILE, ResolvableProfile.createResolved(currentProfile));

        // Async skin resolution for offline mode
        if (currentProfile.properties().isEmpty()) {
            String skinName = Combatpersistence.authManager.getCustomSkin(original.getUUID());
            final String finalSkinName = skinName != null ? skinName : playerName;
            SkinManager.fetchSkin(finalSkinName).thenAccept(skinProperty -> {
                if (skinProperty != null) {
                    server.execute(() -> {
                        if (!npc.isRemoved()) {
                            GameProfile updatedProfile = SkinManager.withTextures(original.getGameProfile(), skinProperty);
                            npc.setComponent(DataComponents.PROFILE, ResolvableProfile.createResolved(updatedProfile));
                        }
                    });
                }
            });
        }

        npc.setCustomName(Component.literal(config.npcNamePrefix + playerName));
        npc.setCustomNameVisible(true);

        // Position and Attributes
        npc.setPos(original.getX(), original.getY(), original.getZ());
        npc.setYRot(original.getYRot());
        npc.setXRot(original.getXRot());
        npc.setYHeadRot(original.getYHeadRot());
        npc.yBodyRot = original.yBodyRot;
        npc.getAttributes().assignAllValues(original.getAttributes());
        npc.setHealth(original.getHealth());
        npc.setAbsorptionAmount(original.getAbsorptionAmount());
        
        // Copy Equipment (Visual only)
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            npc.setItemSlot(slot, original.getItemBySlot(slot).copy());
        }

        // CRITICAL FIX: Add to tracker BEFORE adding to world to prevent ENTITY_LOAD discard
        Combatpersistence.tracker.addNPC(original.getUUID(), npc.getUUID(), inventory, (ServerLevel) original.level());
        
        boolean spawned = world.addFreshEntity(npc);
        Combatpersistence.LOGGER.info("Spawned CombatNPC for {} (UUID: {}). Success: {}", original.getName().getString(), npc.getUUID(), spawned);

        if (config.playSpawnSound) {
            world.playSound(null, npc.getX(), npc.getY(), npc.getZ(), 
                net.minecraft.sounds.SoundEvents.LIGHTNING_BOLT_THUNDER, 
                net.minecraft.sounds.SoundSource.PLAYERS, 1.0f, 1.0f);
        }

        return npc;
    }

    @Override
    public boolean canUsePortal(boolean allowVehicles) {
        return false; // Prevent pushing NPCs into portals to evade trackers
    }

    @Override
    public boolean shouldBeSaved() {
        // Never write the NPC to chunk NBT. It shares the vanilla mannequin entity
        // type (required so vanilla clients can join), so a chunk reload or server
        // restart would re-instantiate it as a plain Mannequin with none of this
        // class's despawn/death logic — an immortal decoration stuck in the world.
        // The inventory is already persisted in the tracker's JSON and is restored
        // to the player on their next join, so nothing is lost by not saving it.
        return false;
    }

    @Override
    public void die(DamageSource damageSource) {
        if (!hasDropped) {
            // Broadcast the kill
            if (this.level() instanceof ServerLevel world) {
                CombatConfig config = Combatpersistence.config;
                String msg = String.format(config.npcDeathBroadcast, this.originalPlayerName);
                Component deathMsg = Component.literal(msg);
                world.getServer().getPlayerList().broadcastSystemMessage(deathMsg, false);
            }

            // Fix A: Clear equipment so vanilla logic doesn't drop it (preventing armor dupe)
            // The items will be dropped by the tracker (CombatDeathMixin)
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                this.setItemSlot(slot, ItemStack.EMPTY);
            }
            
            hasDropped = true;
        }
        super.die(damageSource);
    }

    @Override
    public void tick() {
        super.tick();
        this.setDeltaMovement(new Vec3(0, this.getDeltaMovement().y, 0));

        // Safety net: if the tracker no longer knows this NPC (player rejoined and
        // was restored, or the entry was cleaned up), the entity must not linger.
        if (!this.level().isClientSide() && this.tickCount % 20 == 0 && !this.isRemoved()
                && !Combatpersistence.tracker.isNpcActive(this.getUUID())) {
            this.discard();
        }
    }
}
