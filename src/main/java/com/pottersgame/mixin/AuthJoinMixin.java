package com.pottersgame.mixin;

import com.pottersgame.CombatEvents;
import com.pottersgame.Combatpersistence;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.SocketAddress;

@Mixin(PlayerList.class)
public abstract class AuthJoinMixin {
    @Shadow @Final private MinecraftServer server;

    // Vanilla calls this before disconnectAllPlayersWithProfile. Refuse the
    // incoming connection instead of letting an impostor kick the real owner.
    @Inject(method = "canPlayerLogin", at = @At("HEAD"), cancellable = true)
    private void rejectDuplicate(SocketAddress address, NameAndId profile,
                                 CallbackInfoReturnable<Component> cir) {
        if (hasDuplicate(profile)) cir.setReturnValue(Component.literal("This player is already connected."));
        else if (Combatpersistence.authManager.authRequired(server)) {
            java.util.UUID registered = Combatpersistence.authManager.findRegisteredUuidByName(profile.name(), false);
            if (registered != null && !registered.equals(profile.id())) {
                cir.setReturnValue(Component.literal("Use the exact registered spelling of your player name."));
            }
        }
    }

    @Inject(method = "placeNewPlayer", at = @At("HEAD"), cancellable = true)
    private void protectBeforePackets(Connection connection, ServerPlayer player,
                                      CommonListenerCookie cookie, CallbackInfo ci) {
        // Recheck after configuration: two connections may pass the login check
        // before either has joined the play list.
        if (hasDuplicate(player.nameAndId())) {
            connection.disconnect(Component.literal("This player is already connected."));
            ci.cancel();
            return;
        }
        try {
            CombatEvents.preparePlayerJoin(player, server);
        } catch (RuntimeException error) {
            Combatpersistence.LOGGER.error("Could not protect player state before login; connection refused", error);
            connection.disconnect(Component.literal("Player data is unavailable. Please contact an operator."));
            ci.cancel();
        }
    }

    private boolean hasDuplicate(NameAndId profile) {
        return server.getPlayerList().getPlayers().stream().anyMatch(existing ->
            existing.getUUID().equals(profile.id())
                || existing.getGameProfile().name().equalsIgnoreCase(profile.name()));
    }
}
