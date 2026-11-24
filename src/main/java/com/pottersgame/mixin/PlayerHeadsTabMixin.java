package com.pottersgame.mixin;

import com.pottersgame.PlayerHeads;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Puts the player's head in front of their tab-list name. Injected at RETURN
 * so a display name set by another mod is kept and only gets the head added.
 */
@Mixin(ServerPlayer.class)
public class PlayerHeadsTabMixin {

    @Inject(method = "getTabListDisplayName", at = @At("RETURN"), cancellable = true)
    private void combatpersistence$addHead(CallbackInfoReturnable<Component> cir) {
        if (PlayerHeads.tabEnabled()) {
            cir.setReturnValue(PlayerHeads.tabName((ServerPlayer) (Object) this, cir.getReturnValue()));
        }
    }
}
