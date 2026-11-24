package com.pottersgame.mixin;

import com.pottersgame.PlayerHeads;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ChatType;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Puts the sender's head in front of their name in chat. The bound sender name
 * is not part of the signed message, so this leaves chat signing intact.
 * bind(key, Entity) covers normal chat; bind(key, CommandSourceStack) covers
 * /say, /me, /msg and /teammsg sent by a player.
 */
@Mixin(ChatType.class)
public class PlayerHeadsChatMixin {

    @Inject(method = "bind(Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/network/chat/ChatType$Bound;",
        at = @At("RETURN"), cancellable = true)
    private static void combatpersistence$headForEntity(ResourceKey<ChatType> key, Entity entity,
                                                        CallbackInfoReturnable<ChatType.Bound> cir) {
        if (entity instanceof ServerPlayer player) addHead(player, cir);
    }

    @Inject(method = "bind(Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/commands/CommandSourceStack;)Lnet/minecraft/network/chat/ChatType$Bound;",
        at = @At("RETURN"), cancellable = true)
    private static void combatpersistence$headForSource(ResourceKey<ChatType> key, CommandSourceStack source,
                                                        CallbackInfoReturnable<ChatType.Bound> cir) {
        if (source.getEntity() instanceof ServerPlayer player) addHead(player, cir);
    }

    private static void addHead(ServerPlayer player, CallbackInfoReturnable<ChatType.Bound> cir) {
        if (!PlayerHeads.chatEnabled()) return;
        ChatType.Bound bound = cir.getReturnValue();
        cir.setReturnValue(new ChatType.Bound(bound.chatType(), PlayerHeads.withHead(player, bound.name()), bound.targetName()));
    }
}
