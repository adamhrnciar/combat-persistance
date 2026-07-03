package com.pottersgame.mixin;

import com.pottersgame.Combatpersistence;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntity.class)
public class CombatPickupMixin {

    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void onPlayerTouch(Player player, CallbackInfo ci) {
        if (player instanceof ServerPlayer sp) {
            // Block pickup if not authenticated
            if (!Combatpersistence.authManager.isAuthenticated(sp)) {
                ci.cancel();
                return;
            }
            
            // Block pickup if marked for pending death
            if (Combatpersistence.tracker.isPendingDeath(sp.getUUID())) {
                ci.cancel();
            }
        }
    }
}
