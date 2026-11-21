package com.pottersgame.api;

import com.pottersgame.Combatpersistence;
import net.minecraft.server.level.ServerPlayer;

/**
 * Read-only queries for other server-side mods.
 *
 * @since 1.11.0
 */
public final class CombatPersistenceApi {
    private CombatPersistenceApi() {}

    /**
     * Whether the player currently has an active combat tag, meaning that
     * logging out now would leave a combat-log NPC behind.
     * Call from the server thread.
     */
    public static boolean isInCombat(ServerPlayer player) {
        return Combatpersistence.tracker.isInCombat(player);
    }
}
