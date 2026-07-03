package com.pottersgame.mixin;

import com.pottersgame.Combatpersistence;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;

import java.util.Locale;

@Mixin(ServerGamePacketListenerImpl.class)
public class CombatCommandMixin {

    @Inject(method = "handleContainerClick", at = @At("HEAD"), cancellable = true)
    private void onHandleContainerClick(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        ServerGamePacketListenerImpl handler = (ServerGamePacketListenerImpl) (Object) this;
        if (!Combatpersistence.authManager.isAuthenticated(handler.player)) {
            ci.cancel();
        }
    }

    @Inject(method = "handlePlayerAction", at = @At("HEAD"), cancellable = true)
    private void onHandlePlayerAction(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        ServerGamePacketListenerImpl handler = (ServerGamePacketListenerImpl) (Object) this;
        if (!Combatpersistence.authManager.isAuthenticated(handler.player)) {
            ci.cancel();
        }
    }

    @Inject(method = "handleChatCommand", at = @At("HEAD"), cancellable = true)
    private void onHandleChatCommand(ServerboundChatCommandPacket packet, CallbackInfo ci) {
        ServerGamePacketListenerImpl handler = (ServerGamePacketListenerImpl) (Object) this;
        if (shouldBlockCommand(handler, packet.command())) {
            ci.cancel();
        }
    }

    // Commands with signed arguments arrive through a separate packet; without
    // this injection a modified client could run blocked (or pre-auth) commands
    // by sending them signed.
    @Inject(method = "handleSignedChatCommand", at = @At("HEAD"), cancellable = true)
    private void onHandleSignedChatCommand(ServerboundChatCommandSignedPacket packet, CallbackInfo ci) {
        ServerGamePacketListenerImpl handler = (ServerGamePacketListenerImpl) (Object) this;
        if (shouldBlockCommand(handler, packet.command())) {
            ci.cancel();
        }
    }

    @Inject(method = "handleChat", at = @At("HEAD"), cancellable = true)
    private void onHandleChat(ServerboundChatPacket packet, CallbackInfo ci) {
        ServerGamePacketListenerImpl handler = (ServerGamePacketListenerImpl) (Object) this;
        if (!Combatpersistence.authManager.isAuthenticated(handler.player)) {
            handler.player.sendSystemMessage(Component.literal(Combatpersistence.config.authRequiredForChat));
            ci.cancel();
        }
    }

    /**
     * Shared auth + combat gating for both the plain and signed command packets.
     * Matching is done on the first token with any namespace prefix stripped, so
     * {@code /minecraft:tp} can't slip past a {@code /tp} block. Aliases are a
     * separate command node and still need their own blockedCommands entry
     * (e.g. list both {@code /tp} and {@code /teleport}).
     */
    private static boolean shouldBlockCommand(ServerGamePacketListenerImpl handler, String rawCommand) {
        // First token of the command, lowercased, without a leading slash.
        String base = rawCommand.strip();
        int space = base.indexOf(' ');
        if (space >= 0) base = base.substring(0, space);
        if (base.startsWith("/")) base = base.substring(1);
        base = base.toLowerCase(Locale.ROOT);
        // Namespace-stripped form: "minecraft:tp" -> "tp"
        int colon = base.indexOf(':');
        String stripped = colon >= 0 ? base.substring(colon + 1) : base;

        // 1. Auth blocking
        if (!Combatpersistence.authManager.isAuthenticated(handler.player)) {
            if (stripped.equals("login") || stripped.equals("register")) {
                return false;
            }
            handler.player.sendSystemMessage(Component.literal(Combatpersistence.config.authRequiredForCommand));
            return true;
        }

        // 2. Combat blocking
        if (Combatpersistence.tracker.isInCombat(handler.player)) {
            for (String blocked : Combatpersistence.config.blockedCommands) {
                String b = blocked.toLowerCase(Locale.ROOT);
                if (b.startsWith("/")) b = b.substring(1);
                if (stripped.equals(b) || base.equals(b)) {
                    handler.player.sendSystemMessage(Component.literal(Combatpersistence.config.commandBlockedInCombatMessage));
                    return true;
                }
            }
        }
        return false;
    }
}
