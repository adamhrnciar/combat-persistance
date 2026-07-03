package com.pottersgame;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CombatEvents {
    
    private static final Map<UUID, Long> joinTimes = new ConcurrentHashMap<>();

    public static void register(CombatTracker tracker, CombatConfig config) {
        
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayer victim) {
                if (!Combatpersistence.authManager.isAuthenticated(victim)) return false;

                Entity attackerEntity = source.getEntity();
                
                // Only tag on player-vs-player damage (no PvE/environmental tagging)
                if (attackerEntity instanceof ServerPlayer attacker && Combatpersistence.authManager.isAuthenticated(attacker)) {
                    if (victim != attacker && !victim.getAbilities().instabuild && !attacker.getAbilities().instabuild) {
                        tracker.tag(victim, config.combatTagDurationSeconds);
                        tracker.tag(attacker, config.combatTagDurationSeconds);
                    }
                }
            }
            if (entity instanceof CombatNPC victimNpc) {
                Entity attackerEntity = source.getEntity();
                if (attackerEntity instanceof ServerPlayer attacker && Combatpersistence.authManager.isAuthenticated(attacker)) {
                    if (!attacker.getAbilities().instabuild) {
                        tracker.tag(attacker, config.combatTagDurationSeconds);
                    }
                } else if (attackerEntity instanceof ServerPlayer) {
                    return false; 
                }
            }
            return true; 
        });

        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (player instanceof ServerPlayer sp) {
                if (!Combatpersistence.authManager.isAuthenticated(sp)) return InteractionResult.FAIL;
                if (entity instanceof CombatNPC) return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (player instanceof ServerPlayer sp) {
                if (!Combatpersistence.authManager.isAuthenticated(sp)) {
                    return InteractionResult.FAIL;
                }
            }
            return InteractionResult.PASS;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (player instanceof ServerPlayer sp) {
                if (!Combatpersistence.authManager.isAuthenticated(sp)) {
                    return InteractionResult.FAIL;
                }
                // Ender chest blocking is handled in EnderChestBlockMixin so it reliably
                // stops the menu from opening (the UseBlockCallback did not).
            }
            return InteractionResult.PASS;
        });

        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (player instanceof ServerPlayer sp) {
                if (!Combatpersistence.authManager.isAuthenticated(sp)) {
                    return InteractionResult.FAIL;
                }
            }
            return InteractionResult.PASS;
        });

        // CRITICAL FIX: Chunk Unload Dupe - Discard NPCs on load if they are no longer in active tracker
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof CombatNPC) {
                if (!tracker.isNpcActive(entity.getUUID())) {
                    entity.discard();
                }
            } else if (entity instanceof Mannequin && tracker.isNpcActive(entity.getUUID())) {
                // NPCs saved to disk by older versions reload as plain vanilla
                // mannequins (they share the mannequin entity type) with none of the
                // CombatNPC despawn logic. CombatNPCs are never saved anymore, so any
                // tracked NPC UUID loading from a chunk is such a stale leftover. The
                // tracker entry stays so the player still gets their items on rejoin.
                entity.discard();
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();
            UUID uuid = player.getUUID();
            boolean authenticated = Combatpersistence.authManager.isAuthenticated(player);
            boolean inCombat = tracker.isInCombat(player);
            
            Combatpersistence.LOGGER.info("Player {} disconnected. Auth: {}, InCombat: {}", player.getName().getString(), authenticated, inCombat);

            if (authenticated && inCombat) {
                Combatpersistence.LOGGER.info("Spawning CombatNPC for {}...", player.getName().getString());
                List<ItemStack> fullInv = new ArrayList<>();
                // Copy all items including cursor stack
                for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                    fullInv.add(player.getInventory().getItem(i).copy());
                }
                
                // CRITICAL: Include the item currently held by the cursor (ScreenHandler stack)
                ItemStack cursorStack = player.containerMenu.getCarried().copy();
                if (!cursorStack.isEmpty()) {
                    fullInv.add(cursorStack);
                }

                // CRITICAL FIX: Include items in crafting grids or other temporary slots
                for (Slot slot : player.containerMenu.slots) {
                    if (slot.container != player.getInventory()) {
                        ItemStack stack = slot.getItem();
                        if (!stack.isEmpty()) {
                            fullInv.add(stack.copy());
                            slot.set(ItemStack.EMPTY); // Clear to prevent vanilla from dropping it
                        }
                    }
                }

                CombatNPC npc = CombatNPC.spawn(player, fullInv);
                
                if (npc != null) {
                    Combatpersistence.LOGGER.info("Successfully initiated CombatNPC for {}. NPC UUID: {}", player.getName().getString(), npc.getUUID());
                } else {
                    Combatpersistence.LOGGER.error("Failed to spawn CombatNPC for {}!", player.getName().getString());
                }

                // CRITICAL FIX: Clear everything before the server saves the player data
                player.getInventory().clearContent();
                player.containerMenu.setCarried(ItemStack.EMPTY); // Clear cursor stack
            } else if (inCombat) {
                Combatpersistence.LOGGER.warn("Player {} was in combat but NOT authenticated! No NPC spawned.", player.getName().getString());
            }
            Combatpersistence.authManager.logout(player);
            joinTimes.remove(uuid);
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            UUID uuid = player.getUUID();
            joinTimes.put(uuid, System.currentTimeMillis());
            
            boolean wasOfflineKilled = tracker.checkAndClearOfflineDeath(uuid);
            if (wasOfflineKilled) {
                player.getInventory().clearContent();
                // tracker.markOfflineDeath already adds to tracker's pending deaths
            }

            if (config.enableAuth && (!server.usesAuthentication() || !config.forceAuthInOfflineMode)) {
                if (Combatpersistence.authManager.checkAutoLogin(player)) {
                    player.sendSystemMessage(Component.literal("§aWelcome back! Autologin session verified."));

                    // onAuthenticated handles pending death, pre-auth inventory restore
                    // and combat-NPC cleanup together in the correct order. Calling them
                    // separately here too would double-restore and risk dupes/loss.
                    Combatpersistence.authManager.onAuthenticated(player);

                    String skin = Combatpersistence.authManager.getCustomSkin(uuid);
                    SkinManager.applySkin(player, skin != null ? skin : player.getName().getString());
                } else {
                    player.setNoGravity(true);
                    player.setInvulnerable(true);

                    // Hide the inventory from the client until the password is verified, so
                    // an impostor sharing this offline UUID can't see the real owner's items.
                    if (config.hideInventoryBeforeAuth) {
                        Combatpersistence.authManager.stashInventory(player);
                    }

                    if (config.hideCoordinatesBeforeAuth) {
                        Combatpersistence.authManager.saveLocation(player);
                        ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, Identifier.parse(config.lobbyDimension));
                        ServerLevel lobbyLevel = server.getLevel(dimKey);
                        if (lobbyLevel != null) {
                            TeleportTransition transition = new TeleportTransition(
                                lobbyLevel, 
                                new Vec3(config.lobbyX, config.lobbyY, config.lobbyZ), 
                                Vec3.ZERO, 0, 0, 
                                TeleportTransition.DO_NOTHING
                            );
                            player.teleport(transition);
                        }
                    }
                    
                    if (Combatpersistence.authManager.isRegistered(uuid)) {
                        player.sendSystemMessage(Component.literal(config.loginPrompt));
                    } else {
                        player.sendSystemMessage(Component.literal(config.registerPrompt));
                    }
                }
            } else {
                // Auth is off (or this is an online-mode server). Recover any inventory
                // that was stashed while auth was still enabled, so disabling auth can
                // never strand a player's items in the pending-inventory file.
                Combatpersistence.authManager.restorePreAuthInventory(player);
                cleanUpNpc(player, tracker, server);
                // If no auth, handle pending death immediately
                handlePendingDeath(player);

                // If this is an offline server, apply the skin (online servers do this automatically)
                if (!server.usesAuthentication()) {
                    String skin = Combatpersistence.authManager.getCustomSkin(uuid);
                    SkinManager.applySkin(player, skin != null ? skin : player.getName().getString());
                }
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long now = System.currentTimeMillis();

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                UUID uuid = player.getUUID();
                boolean authenticated = Combatpersistence.authManager.isAuthenticated(player);

                if (config.enableAuth && !authenticated) {
                    Long joinTime = joinTimes.get(uuid);
                    if (joinTime != null && (now - joinTime) > (config.authTimeoutSeconds * 1000L)) {
                        player.connection.disconnect(Component.literal(config.loginTimeoutMessage));
                        continue;
                    }

                    // CRITICAL FIX: Ender Pearl Lobby Escape - continuously lock to lobby and clear pearls
                    if (config.hideCoordinatesBeforeAuth) {
                        ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, Identifier.parse(config.lobbyDimension));
                        if (!player.level().dimension().equals(dimKey)) {
                            ServerLevel lobbyLevel = server.getLevel(dimKey);
                            if (lobbyLevel != null) {
                                TeleportTransition transition = new TeleportTransition(
                                    lobbyLevel, 
                                    new Vec3(config.lobbyX, config.lobbyY, config.lobbyZ), 
                                    Vec3.ZERO, 0, 0, 
                                    TeleportTransition.DO_NOTHING
                                );
                                player.teleport(transition);
                            }
                        } else if (player.distanceToSqr(config.lobbyX, config.lobbyY, config.lobbyZ) > 100) {
                            TeleportTransition transition = new TeleportTransition(
                                (ServerLevel) player.level(), 
                                new Vec3(config.lobbyX, config.lobbyY, config.lobbyZ), 
                                Vec3.ZERO, 0, 0, 
                                TeleportTransition.DO_NOTHING
                            );
                            player.teleport(transition);
                        }
                    }

                    // Clear any thrown ender pearls to prevent teleportation exploits
                    Set<ThrownEnderpearl> pearls = player.getEnderPearls();
                    if (!pearls.isEmpty()) {
                        for (ThrownEnderpearl pearl : pearls) {
                            pearl.discard();
                        }
                        pearls.clear();
                    }

                    if (server.getTickCount() % 100 == 0) {
                        if (Combatpersistence.authManager.isRegistered(uuid)) {
                            player.sendSystemMessage(Component.literal(config.loginPrompt), true);
                        } else {
                            player.sendSystemMessage(Component.literal(config.registerPrompt), true);
                        }
                    }
                }
            }
        });

        ServerTickEvents.END_LEVEL_TICK.register(world -> {
            tracker.tick(world);
            Map<UUID, Long> tagged = tracker.getTaggedPlayers();
            if (!tagged.isEmpty()) {
                long now = System.currentTimeMillis();
                for (Map.Entry<UUID, Long> entry : tagged.entrySet()) {
                    ServerPlayer player = (ServerPlayer) world.getPlayerInAnyDimension(entry.getKey());
                    if (player != null && player.level() == world) {
                        long remainingMillis = Math.max(0, entry.getValue() - now);
                        if (remainingMillis > 0) {
                            int seconds = (int) Math.ceil(remainingMillis / 1000.0);
                            String msg = String.format(config.combatMessage, seconds);
                            player.sendSystemMessage(Component.literal(msg), true);
                        }
                    }
                }
            }
        });
    }

    public static void handlePendingDeath(ServerPlayer player) {
        if (Combatpersistence.tracker.isPendingDeath(player.getUUID())) {
            player.getInventory().clearContent();
            player.setHealth(0.0f);
            player.die(player.level().damageSources().generic());
            Combatpersistence.tracker.removePendingDeath(player.getUUID());
        }
    }

    public static void cleanUpNpc(ServerPlayer player, CombatTracker tracker, net.minecraft.server.MinecraftServer server) {
        // Discard entity if it exists
        UUID npcUuid = tracker.getNPCForPlayer(player.getUUID());
        if (npcUuid != null) {
            for (ServerLevel level : server.getAllLevels()) {
                Entity npcEntity = level.getEntity(npcUuid);
                if (npcEntity != null) {
                    npcEntity.discard();
                    break;
                }
            }
        }
        // Restore inventory from tracker (handles removing entry)
        tracker.restoreInventory(player);
    }
}
