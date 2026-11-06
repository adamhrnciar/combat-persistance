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
import net.minecraft.network.protocol.PacketUtils;

import java.util.Locale;

@Mixin(ServerGamePacketListenerImpl.class)
public class CombatCommandMixin {

    // Gate all state-changing packet paths, including creative and operator
    // packets. An offline-mode impostor inherits the account's vanilla rights.
    // Callback-only injectors intentionally omit each packet's varying type.
    @Inject(method = {"handleMovePlayer", "handleMoveVehicle", "handlePlayerInput", "handlePlayerPositionChange",
        "handlePlayerAbilities", "handleAttack", "handlePunch", "handleInteract", "handleUseItemOn", "handleUseItem",
        "handleTeleportToEntityPacket", "handleSpectatorAction", "handlePlayerCommand", "handleClientCommand",
        "handleSetCreativeModeSlot", "handlePlaceRecipe", "handleContainerButtonClick", "handleContainerClose",
        "handleContainerSlotStateChanged", "handleBundleItemSelectedPacket", "handleSetCarriedItem",
        "handleRecipeBookSeenRecipePacket", "handleRecipeBookChangeSettingsPacket", "handleSeenAdvancements",
        "handleCustomCommandSuggestions", "handleEntityTagQuery", "handleBlockEntityTagQuery",
        "handleSetCommandBlock", "handleSetCommandMinecart", "handleSetGameRule", "handleSetStructureBlock",
        "handleSetTestBlock", "handleTestInstanceBlockAction", "handleSetJigsawBlock", "handleJigsawGenerate",
        "handleRenameItem", "handleSetBeaconPacket", "handleSelectTrade", "handleEditBook", "handleSignUpdate",
        "handleChangeDifficulty", "handleChangeGameMode", "handleLockDifficulty", "handleDebugSubscriptionRequest",
        "handlePickItemFromBlock", "handlePickItemFromEntity", "handlePaddleBoat"},
        at = @At("HEAD"), cancellable = true)
    private void blockUnauthenticatedActions(CallbackInfo ci) {
        ServerGamePacketListenerImpl handler = (ServerGamePacketListenerImpl) (Object) this;
        if (!Combatpersistence.authManager.isAuthenticated(handler.player)) ci.cancel();
    }

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
        PacketUtils.ensureRunningOnSameThread(packet, handler, handler.player.level());
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
        PacketUtils.ensureRunningOnSameThread(packet, handler, handler.player.level());
        if (shouldBlockCommand(handler, packet.command())) {
            ci.cancel();
        }
    }

    @Inject(method = "handleChat", at = @At("HEAD"), cancellable = true)
    private void onHandleChat(ServerboundChatPacket packet, CallbackInfo ci) {
        ServerGamePacketListenerImpl handler = (ServerGamePacketListenerImpl) (Object) this;
        PacketUtils.ensureRunningOnSameThread(packet, handler, handler.player.level());
        if (!Combatpersistence.authManager.isAuthenticated(handler.player)) {
            handler.player.sendSystemMessage(Component.literal(Combatpersistence.config.authRequiredForChat));
            ci.cancel();
        }
    }

    // Chat command execution is queued again by vanilla. Recheck at execution
    // time so a command queued before a combat tag cannot escape the block.
    @Inject(method = "performUnsignedChatCommand", at = @At("HEAD"), cancellable = true)
    private void recheckUnsignedCommand(String command, CallbackInfo ci) {
        if (shouldBlockCommand((ServerGamePacketListenerImpl) (Object) this, command)) ci.cancel();
    }

    @Inject(method = "performSignedChatCommand", at = @At("HEAD"), cancellable = true)
    private void recheckSignedCommand(ServerboundChatCommandSignedPacket packet,
                                      net.minecraft.network.chat.LastSeenMessages messages, CallbackInfo ci) {
        if (shouldBlockCommand((ServerGamePacketListenerImpl) (Object) this, packet.command())) ci.cancel();
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
            if (base.equals("login") || base.equals("register")) {
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
