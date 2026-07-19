package com.pottersgame.mixin;

import com.pottersgame.CombatEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Combat-log disconnect handling must run on the main thread and strictly
 * before vanilla writes player.dat. PlayerList.remove is that point: it runs
 * only on the server thread and calls save(player) as nearly its first action,
 * so a HEAD inject is ordered before the save. Fabric's DISCONNECT event is
 * unusable here — on a real combat log (socket drop) it fires from
 * Connection.channelInactive on the Netty thread, unsynchronized with that
 * save, which is exactly the race that produced the inventory-dupe bug.
 */
@Mixin(PlayerList.class)
public class PlayerListRemoveMixin {

    @Inject(method = "remove(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("HEAD"))
    private void onRemove(ServerPlayer player, CallbackInfo ci) {
        CombatEvents.handlePlayerRemove(player);
    }
}
