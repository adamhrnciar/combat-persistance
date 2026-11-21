package com.pottersgame.api;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Public, stable server-side events for combat-log NPCs.
 *
 * <p>Combat Persistence spawns an NPC (a vanilla mannequin) holding a player's
 * inventory when that player disconnects while combat-tagged. These events let
 * other mods react when such an NPC is killed and decide which stored items
 * survive the kill.
 *
 * <p>All callbacks run on the server thread. A listener that throws is logged
 * and skipped; it never aborts the NPC death or the inventory handling. Register
 * listeners from your mod initializer.
 *
 * <p>Slot numbers are vanilla {@code Inventory} container indices: {@code 0-35}
 * main storage (hotbar first), {@code 36-39} armor (feet, legs, chest, head),
 * {@code 40} offhand, higher indices any further equipment slots, and
 * {@link #NO_SLOT} for items without a stable slot (cursor stack, crafting grid
 * and other temporary menu inputs).
 *
 * @since 1.11.0
 */
public final class CombatNpcEvents {
    private static final Logger LOGGER = LoggerFactory.getLogger("combatpersistence");

    /** Slot value for items that had no stable inventory slot (cursor or crafting input). */
    public static final int NO_SLOT = -1;

    private CombatNpcEvents() {}

    /**
     * Fired once when a combat-log NPC dies and its death has been committed.
     *
     * <p>When this fires, the owner's death is durably recorded (they die on
     * their next join), the dropped items are already in the world and any items
     * kept by {@link #KEEP_ON_NPC_DEATH} are durably stored for the owner. It
     * does not fire when the death could not be committed (for example a disk
     * write failure); then the owner keeps their whole inventory and nothing
     * drops.
     */
    public static final Event<NpcKilled> NPC_KILLED = EventFactory.createArrayBacked(NpcKilled.class,
        listeners -> (level, ownerUuid, ownerName, npc, source) -> {
            for (NpcKilled listener : listeners) {
                try {
                    listener.onNpcKilled(level, ownerUuid, ownerName, npc, source);
                } catch (RuntimeException error) {
                    LOGGER.error("A combat NPC kill listener failed", error);
                }
            }
        });

    /**
     * Asked once per stored item when a combat-log NPC dies, before any item
     * drops. If any listener returns {@code true}, the item does not drop.
     * Instead it is stored durably together with the owner's death record and
     * returned to the owner after their next join, once they have died and
     * respawned. With no listeners every item drops, exactly as before this API.
     *
     * <p>A listener that throws counts as {@code false} for that item.
     */
    public static final Event<KeepOnNpcDeath> KEEP_ON_NPC_DEATH = EventFactory.createArrayBacked(KeepOnNpcDeath.class,
        listeners -> (ownerUuid, stack, slot, isEquipmentSlot) -> {
            for (KeepOnNpcDeath listener : listeners) {
                try {
                    if (listener.shouldKeep(ownerUuid, stack, slot, isEquipmentSlot)) return true;
                } catch (RuntimeException error) {
                    LOGGER.error("A combat NPC keep-item listener failed; treating it as 'do not keep'", error);
                }
            }
            return false;
        });

    @FunctionalInterface
    public interface NpcKilled {
        /**
         * @param level     level the NPC died in
         * @param ownerUuid UUID of the player who combat-logged
         * @param ownerName name of that player when the NPC was spawned
         * @param npc       the NPC entity (already dying; do not modify its inventory)
         * @param source    the damage source that killed it; {@code source.getEntity()} is the killer, if any
         */
        void onNpcKilled(ServerLevel level, UUID ownerUuid, String ownerName, LivingEntity npc, DamageSource source);
    }

    @FunctionalInterface
    public interface KeepOnNpcDeath {
        /**
         * @param ownerUuid       UUID of the player who combat-logged
         * @param stack           a copy of the stored item; changing it has no effect
         * @param slot            original inventory slot (see class documentation), or {@link #NO_SLOT}
         * @param isEquipmentSlot {@code true} for armor, offhand and other equipment slots ({@code slot >= 36})
         * @return {@code true} to keep the item for its owner instead of dropping it
         */
        boolean shouldKeep(UUID ownerUuid, ItemStack stack, int slot, boolean isEquipmentSlot);
    }
}
