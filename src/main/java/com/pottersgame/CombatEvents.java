package com.pottersgame;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.ItemStack;
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
                    Combatpersistence.debug("Discarded stale CombatNPC {} on load (no tracker entry)", entity.getUUID());
                }
            } else if (entity instanceof Mannequin && tracker.isNpcActive(entity.getUUID())) {
                // NPCs saved to disk by older versions reload as plain vanilla
                // mannequins (they share the mannequin entity type) with none of the
                // CombatNPC despawn logic. CombatNPCs are never saved anymore, so any
                // tracked NPC UUID loading from a chunk is such a stale leftover. The
                // tracker entry stays so the player still gets their items on rejoin.
                entity.discard();
                Combatpersistence.debug("Discarded stale legacy mannequin {} on load (tracker entry kept)", entity.getUUID());
            }
        });

        // NOTE: combat-log handling deliberately does NOT use Fabric's
        // DISCONNECT event — it lives in handlePlayerRemove, invoked from
        // PlayerListRemoveMixin at the HEAD of PlayerList.remove. On a real
        // combat log (socket drop) the Fabric event fires from
        // Connection.channelInactive on the NETTY thread and races the main
        // thread's PlayerList.remove -> save(player): the save frequently won,
        // player.dat kept the full inventory, and the tracker restored a second
        // copy on rejoin — the item-dupe bug. The remove hook is always on the
        // main thread and always runs before that save.

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            UUID uuid = player.getUUID();

            // A live login lockout (account or source IP) refuses the connection
            // outright — checked before any other join processing so a locked-out
            // player can't even reach the lobby to retry.
            if (Combatpersistence.authManager.authRequired(server)) {
                int lockoutSecs = Combatpersistence.authManager.getLockoutSecondsRemaining(player);
                if (lockoutSecs > 0) {
                    player.connection.disconnect(Component.literal(
                        String.format(config.loginLockedOutMessage, lockoutSecs)));
                    return;
                }
            }

            joinTimes.put(uuid, System.currentTimeMillis());

            boolean wasOfflineKilled = tracker.checkAndClearOfflineDeath(uuid);
            if (wasOfflineKilled) {
                player.getInventory().clearContent();
                // tracker.markOfflineDeath already adds to tracker's pending deaths
            }

            if (Combatpersistence.authManager.authRequired(server)) {
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
                        teleportToLobby(server, player, config);
                    }

                    if (Combatpersistence.authManager.isRegistered(uuid)) {
                        player.sendSystemMessage(Component.literal(config.loginPrompt));
                    } else {
                        player.sendSystemMessage(Component.literal(config.registerPrompt));
                    }
                }
            } else {
                // Auth doesn't apply (disabled, or this is an online-mode server).
                // Recover any state left behind from when auth WAS active, so turning
                // auth off can never strand a player's items in the pending-inventory
                // file or leave them floating and invulnerable from a pre-auth join.
                player.setNoGravity(false);
                player.setInvulnerable(false);
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

                // isAuthenticated is true whenever the auth flow doesn't apply
                // (auth disabled, online-mode server), so this only ever gates
                // players actually stuck in the login window.
                if (!Combatpersistence.authManager.isAuthenticated(player)) {
                    Long joinTime = joinTimes.get(uuid);
                    if (joinTime != null && (now - joinTime) > (config.authTimeoutSeconds * 1000L)) {
                        player.connection.disconnect(Component.literal(config.loginTimeoutMessage));
                        continue;
                    }

                    // CRITICAL FIX: Ender Pearl Lobby Escape - continuously lock to lobby and clear pearls
                    if (config.hideCoordinatesBeforeAuth) {
                        if (!player.level().dimension().equals(config.lobbyDimensionKey())
                                || player.distanceToSqr(config.lobbyX, config.lobbyY, config.lobbyZ) > 100) {
                            teleportToLobby(server, player, config);
                        }
                    }

                    // Clear any thrown ender pearls to prevent teleportation exploits.
                    // Iterate a copy: discarding a pearl deregisters it from the
                    // player's live set, which would throw CME mid-iteration.
                    Set<ThrownEnderpearl> pearls = player.getEnderPearls();
                    if (!pearls.isEmpty()) {
                        for (ThrownEnderpearl pearl : List.copyOf(pearls)) {
                            pearl.discard();
                        }
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

            // Combat tag expiry + NPC discard, once per tick (this used to run per
            // level, i.e. three times per tick on a vanilla server).
            tracker.tick(server);

            Map<UUID, Long> tagged = tracker.getTaggedPlayers();
            if (!tagged.isEmpty()) {
                for (Map.Entry<UUID, Long> entry : tagged.entrySet()) {
                    ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                    if (player != null) {
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

    /**
     * Disconnect handling, called from PlayerListRemoveMixin at the HEAD of
     * PlayerList.remove(ServerPlayer) — guaranteed main thread and guaranteed
     * to run before vanilla writes player.dat inside that same method, so the
     * combat-log inventory clear is always captured by the save. Not called on
     * server stop: /stop saves everyone via saveAll() and never runs
     * PlayerList.remove, so a tagged player simply keeps their items there
     * (shutdown is not combat logging).
     */
    public static void handlePlayerRemove(ServerPlayer player) {
        UUID uuid = player.getUUID();
        CombatTracker tracker = Combatpersistence.tracker;
        boolean authenticated = Combatpersistence.authManager.isAuthenticated(player);
        boolean inCombat = tracker.isInCombat(player);

        Combatpersistence.LOGGER.info("Player {} disconnected. Thread: {}, Auth: {}, InCombat: {}",
            player.getName().getString(), Thread.currentThread().getName(), authenticated, inCombat);

        try {
            if (authenticated && inCombat) {
                if (tracker.getNPCForPlayer(uuid) != null) {
                    // A tracker entry already holds this player's items — snapshotting
                    // the (already cleared) live inventory again would overwrite the
                    // durable copy with an empty one.
                    Combatpersistence.LOGGER.error(
                        "Combat-log handling re-entered for {} with a live tracker entry — skipping snapshot",
                        player.getName().getString());
                } else {
                    Combatpersistence.LOGGER.info("Spawning CombatNPC for {}...", player.getName().getString());
                    List<SlottedInventory.SlottedStack> fullInv = SlottedInventory.snapshot(player);

                    int main = 0, armor = 0, offhand = 0, slotless = 0;
                    for (SlottedInventory.SlottedStack item : fullInv) {
                        if (item.slot() == SlottedInventory.NO_SLOT) slotless++;
                        else if (item.slot() < SlottedInventory.MAIN_SIZE) main++;
                        else if (item.slot() == SlottedInventory.OFFHAND_SLOT) offhand++;
                        else armor++;
                    }
                    Combatpersistence.debug("Snapshot for {}: {} items ({} main, {} armor, {} offhand, {} cursor/crafting)",
                        player.getName().getString(), fullInv.size(), main, armor, offhand, slotless);

                    CombatNPC npc = CombatNPC.spawn(player, fullInv);
                    if (npc != null) {
                        Combatpersistence.LOGGER.info("Successfully initiated CombatNPC for {}. NPC UUID: {}",
                            player.getName().getString(), npc.getUUID());
                    } else {
                        Combatpersistence.LOGGER.error("Failed to spawn CombatNPC for {}!", player.getName().getString());
                    }

                    // Only destroy the live copy once the snapshot is durably on
                    // disk (addNPC saves synchronously inside spawn). Vanilla
                    // writes player.dat right after this hook returns, so it
                    // saves the emptied inventory.
                    if (tracker.getNPCForPlayer(uuid) != null) {
                        player.getInventory().clearContent();
                        player.containerMenu.setCarried(ItemStack.EMPTY);
                        Combatpersistence.debug("Cleared live inventory of {} after snapshot", player.getName().getString());
                    } else {
                        Combatpersistence.LOGGER.error(
                            "No durable tracker entry for {} after NPC spawn — live inventory NOT cleared",
                            player.getName().getString());
                    }
                }
            } else if (inCombat) {
                Combatpersistence.LOGGER.warn("Player {} was in combat but NOT authenticated! No NPC spawned.",
                    player.getName().getString());
            }
        } catch (Exception e) {
            // This hook sits at the head of PlayerList.remove — an escaping
            // exception would abort vanilla's player.dat save and player-list
            // cleanup for this player.
            Combatpersistence.LOGGER.error("Combat-log disconnect handling failed for {}", player.getName().getString(), e);
        }
        Combatpersistence.authManager.logout(player);
        joinTimes.remove(uuid);
    }

    private static void teleportToLobby(MinecraftServer server, ServerPlayer player, CombatConfig config) {
        ServerLevel lobbyLevel = server.getLevel(config.lobbyDimensionKey());
        if (lobbyLevel == null) return;
        player.teleport(new TeleportTransition(
            lobbyLevel,
            new Vec3(config.lobbyX, config.lobbyY, config.lobbyZ),
            Vec3.ZERO, 0, 0,
            TeleportTransition.DO_NOTHING
        ));
    }

    public static void handlePendingDeath(ServerPlayer player) {
        if (Combatpersistence.tracker.isPendingDeath(player.getUUID())) {
            Combatpersistence.debug("Executing pending combat-log death for {} on join", player.getName().getString());
            player.getInventory().clearContent();
            player.setHealth(0.0f);
            player.die(player.level().damageSources().generic());
            Combatpersistence.tracker.removePendingDeath(player.getUUID());
        }
    }

    public static void cleanUpNpc(ServerPlayer player, CombatTracker tracker, MinecraftServer server) {
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
