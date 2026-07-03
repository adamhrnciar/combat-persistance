package com.pottersgame;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class AuthCommands {

    // Last /skin use per player, for the anti-API-spam cooldown. In-memory only.
    private static final Map<UUID, Long> lastSkinUse = new ConcurrentHashMap<>();

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            
            dispatcher.register(Commands.literal("register")
                .then(Commands.argument("password", StringArgumentType.word())
                .then(Commands.argument("confirmPassword", StringArgumentType.word())
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    String pwd = StringArgumentType.getString(context, "password");
                    String confirm = StringArgumentType.getString(context, "confirmPassword");

                    if (Combatpersistence.authManager.isAuthenticated(player)) {
                        player.sendSystemMessage(Component.literal(Combatpersistence.config.alreadyLoggedIn));
                        return 0;
                    }
                    if (Combatpersistence.authManager.isRegistered(player.getUUID())) {
                        player.sendSystemMessage(Component.literal(Combatpersistence.config.alreadyRegistered));
                        return 0;
                    }
                    if (!pwd.equals(confirm)) {
                        player.sendSystemMessage(Component.literal(Combatpersistence.config.passwordMismatch));
                        return 0;
                    }
                    if (pwd.length() < Combatpersistence.config.minPasswordLength) {
                        player.sendSystemMessage(Component.literal(
                            String.format(Combatpersistence.config.passwordTooShort, Combatpersistence.config.minPasswordLength)));
                        return 0;
                    }
                    // BCrypt silently truncates past 72 bytes; refuse instead of
                    // storing a hash of a truncated password.
                    if (pwd.length() > Combatpersistence.config.maxPasswordLength) {
                        player.sendSystemMessage(Component.literal(
                            String.format(Combatpersistence.config.passwordTooLong, Combatpersistence.config.maxPasswordLength)));
                        return 0;
                    }

                    CompletableFuture.runAsync(() -> {
                        Combatpersistence.authManager.register(player, pwd);
                        context.getSource().getServer().execute(() -> {
                            // Verify player is still connected and valid after async hashing
                            if (player.connection != null && !player.isRemoved()) {
                                Combatpersistence.authManager.onAuthenticated(player);
                                player.sendSystemMessage(Component.literal(Combatpersistence.config.registerSuccess));
                                SkinManager.applySkin(player, player.getName().getString());
                            }
                        });
                    });
                    
                    return 1;
                }))));

            dispatcher.register(Commands.literal("login")
                .then(Commands.argument("password", StringArgumentType.word())
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    String pwd = StringArgumentType.getString(context, "password");

                    if (Combatpersistence.authManager.isAuthenticated(player)) {
                        player.sendSystemMessage(Component.literal(Combatpersistence.config.alreadyLoggedIn));
                        return 0;
                    }

                    CompletableFuture.runAsync(() -> {
                        AuthManager.LoginResult result = Combatpersistence.authManager.login(player, pwd);
                        context.getSource().getServer().execute(() -> {
                            // Verify player is still connected and valid after async hashing
                            if (player.connection == null || player.isRemoved()) return;
                            switch (result) {
                                case SUCCESS -> {
                                    Combatpersistence.authManager.onAuthenticated(player);
                                    player.sendSystemMessage(Component.literal(Combatpersistence.config.loginSuccess));
                                    String skin = Combatpersistence.authManager.getCustomSkin(player.getUUID());
                                    SkinManager.applySkin(player, skin != null ? skin : player.getName().getString());
                                }
                                case LOCKED_OUT -> {
                                    int secs = Combatpersistence.authManager.getLockoutSecondsRemaining(player);
                                    Component msg = Component.literal(
                                        String.format(Combatpersistence.config.loginLockedOutMessage, secs));
                                    // Locked out means kicked: the join handler refuses the
                                    // connection until the lockout expires.
                                    player.connection.disconnect(msg);
                                }
                                case WRONG_PASSWORD -> {
                                    if (Combatpersistence.config.kickOnWrongPassword) {
                                        player.connection.disconnect(Component.literal(Combatpersistence.config.incorrectPassword));
                                    } else {
                                        player.sendSystemMessage(Component.literal(Combatpersistence.config.incorrectPassword));
                                    }
                                }
                            }
                        });
                    });
                    
                    return 1;
                })));

            // Operator-only: wipe a player's registration so they must register
            // again (e.g. forgotten password). Works on offline players too — the
            // name is resolved to a stored/offline UUID. Online targets are kicked
            // so they re-enter the normal join/lobby/register flow on reconnect.
            dispatcher.register(Commands.literal("authreset")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.argument("target", StringArgumentType.word())
                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                    ctx.getSource().getServer().getPlayerList().getPlayers().stream()
                        .map(p -> p.getName().getString()), builder))
                .executes(context -> {
                    String name = StringArgumentType.getString(context, "target");
                    MinecraftServer server = context.getSource().getServer();

                    // Prefer an online player (gives the real UUID and lets us kick),
                    // otherwise resolve a registered/offline UUID by name.
                    ServerPlayer online = server.getPlayerList().getPlayerByName(name);
                    UUID targetUuid = (online != null)
                        ? online.getUUID()
                        : Combatpersistence.authManager.findRegisteredUuidByName(name, server.usesAuthentication());

                    if (targetUuid == null || !Combatpersistence.authManager.resetUser(targetUuid)) {
                        context.getSource().sendSystemMessage(
                            Component.literal(String.format(Combatpersistence.config.accountResetNotRegistered, name)));
                        return 0;
                    }

                    if (online != null) {
                        online.connection.disconnect(Component.literal(Combatpersistence.config.accountResetKickMessage));
                    }
                    context.getSource().sendSystemMessage(
                        Component.literal(String.format(Combatpersistence.config.accountResetSuccess, name)));
                    Combatpersistence.LOGGER.info("Operator {} reset the account of {}",
                        context.getSource().getTextName(), name);
                    return 1;
                })));

            dispatcher.register(Commands.literal("skin")
                .then(Commands.argument("name", StringArgumentType.word())
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    if (!Combatpersistence.authManager.isAuthenticated(player)) {
                        player.sendSystemMessage(Component.literal(Combatpersistence.config.authRequiredForSkin));
                        return 0;
                    }
                    // Cooldown: each uncached lookup costs shared Mojang API quota.
                    long cooldownMs = Combatpersistence.config.skinCommandCooldownSeconds * 1000L;
                    long now = System.currentTimeMillis();
                    Long lastUse = lastSkinUse.get(player.getUUID());
                    if (lastUse != null && (now - lastUse) < cooldownMs) {
                        int secs = (int) Math.ceil((cooldownMs - (now - lastUse)) / 1000.0);
                        player.sendSystemMessage(Component.literal(
                            String.format(Combatpersistence.config.skinCooldownMessage, secs)));
                        return 0;
                    }
                    lastSkinUse.put(player.getUUID(), now);
                    String skinName = StringArgumentType.getString(context, "name");
                    Combatpersistence.authManager.setCustomSkin(player.getUUID(), skinName);
                    SkinManager.applySkin(player, skinName);
                    player.sendSystemMessage(Component.literal(String.format(Combatpersistence.config.skinAppliedMessage, skinName)));
                    return 1;
                })));
        });
    }
}
