package com.pottersgame.mixin;

import com.pottersgame.Combatpersistence;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public class CombatDeathMixin {

    @Inject(method = "die", at = @At("HEAD"))
    private void onDie(DamageSource damageSource, CallbackInfo ci) {
        LivingEntity entity = (LivingEntity) (Object) this;
        Combatpersistence.tracker.handleEntityDeath(entity);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(CallbackInfo ci) {
        if ((Object)this instanceof ServerPlayer player) {
            // isAuthenticated already covers "auth doesn't apply here" (disabled
            // or online-mode server), so no extra enableAuth check is needed.
            if (!Combatpersistence.authManager.isAuthenticated(player)) {
                // Stop momentum
                player.setDeltaMovement(0, 0, 0);
                
                // Apply Slowness 255 and Mining Fatigue 255 while unauthenticated
                // Use INFINITE_DURATION to ensure it stays until cleared by AuthManager
                if (player.level() instanceof ServerLevel serverLevel) {
                    if (serverLevel.getServer().getTickCount() % 20 == 0) {
                        player.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, MobEffectInstance.INFINITE_DURATION, 255, false, false));
                        player.addEffect(new MobEffectInstance(MobEffects.MINING_FATIGUE, MobEffectInstance.INFINITE_DURATION, 255, false, false));
                    }
                }
            }
        }
    }

    @Inject(method = "isInvulnerableTo", at = @At("HEAD"), cancellable = true)
    private void onIsInvulnerableTo(ServerLevel level, DamageSource source, CallbackInfoReturnable<Boolean> cir) {
        if ((Object)this instanceof ServerPlayer player) {
            if (!Combatpersistence.authManager.isAuthenticated(player)) {
                // Allow damage if they are supposed to die on join, even if not authenticated yet.
                // This prevents them from being stuck invulnerable if the death processing is delayed.
                if (Combatpersistence.tracker.isPendingDeath(player.getUUID())) {
                    cir.setReturnValue(false);
                    return;
                }
                cir.setReturnValue(true);
            }
        }
    }
}
