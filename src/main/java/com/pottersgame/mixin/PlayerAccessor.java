package com.pottersgame.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Mapping-safe replacement for the old {@code getDeclaredField("gameProfile")}
 * reflection, which only worked in dev — in production the field is remapped to
 * an intermediary name and the lookup failed, silently breaking /skin.
 */
@Mixin(Player.class)
public interface PlayerAccessor {
    @Accessor("gameProfile")
    @Mutable
    void combatpersistence$setGameProfile(GameProfile profile);
}
