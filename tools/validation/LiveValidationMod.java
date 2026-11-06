package com.pottersgame.validation;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.pottersgame.CombatNPC;
import com.pottersgame.Combatpersistence;
import com.pottersgame.SlottedInventory;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Disposable-world test instrumentation. Compile into a separate test-only mod.
 * Never package this class or its metadata in a distributed release.
 */
public final class LiveValidationMod implements ModInitializer {
    @Override public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> dispatcher.register(
            Commands.literal("securityprobe")
                .requires(source -> source.getEntity() == null)
                .then(Commands.argument("action", StringArgumentType.word())
                    .then(Commands.argument("player", StringArgumentType.word())
                        .executes(context -> run(context.getSource(), StringArgumentType.getString(context, "action"),
                            StringArgumentType.getString(context, "player"), -1))
                        .then(Commands.argument("diamonds", IntegerArgumentType.integer(0))
                            .executes(context -> run(context.getSource(), StringArgumentType.getString(context, "action"),
                                StringArgumentType.getString(context, "player"), IntegerArgumentType.getInteger(context, "diamonds"))))))));
    }

    private static int run(CommandSourceStack source, String action, String name, int expectedDiamonds) {
        try {
            ServerPlayer player = source.getServer().getPlayerList().getPlayerByName(name);
            UUID uuid = player != null ? player.getUUID()
                : UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            if (action.equals("absentnpc")) {
                if (Combatpersistence.tracker.getNPCForPlayer(uuid) != null) throw new IllegalStateException("Unexpected NPC tracker entry");
                source.sendSuccess(() -> Component.literal("SECURITY_PROBE PASS absentnpc " + name), false);
                return 1;
            }
            if (action.equals("killnpc")) {
                UUID npcUuid = Combatpersistence.tracker.getNPCForPlayer(uuid);
                if (npcUuid == null) throw new IllegalStateException("Missing NPC tracker entry");
                CombatNPC npc = null;
                for (ServerLevel level : source.getServer().getAllLevels()) {
                    Entity entity = level.getEntity(npcUuid);
                    if (entity instanceof CombatNPC found) { npc = found; break; }
                }
                if (npc == null) throw new IllegalStateException("NPC entity is not loaded");
                npc.setHealth(0);
                npc.die(npc.level().damageSources().generic());
                npc.die(npc.level().damageSources().generic());
                int diamonds = 0;
                for (ServerLevel level : source.getServer().getAllLevels()) {
                    for (Entity entity : level.getAllEntities()) {
                        if (entity instanceof ItemEntity item && item.getItem().is(Items.DIAMOND)) diamonds += item.getItem().getCount();
                    }
                }
                if (expectedDiamonds >= 0 && diamonds != expectedDiamonds) throw new IllegalStateException("World diamond count " + diamonds);
                if (!Combatpersistence.tracker.isPendingDeath(uuid)) throw new IllegalStateException("Death marker missing");
                source.sendSuccess(() -> Component.literal("SECURITY_PROBE PASS killnpc " + name), false);
                return 1;
            }
            if (player == null) throw new IllegalStateException("Player is not online");
            switch (action) {
                case "seed", "seedoverflow", "seedanvil" -> {
                    if (!Combatpersistence.authManager.isAuthenticated(player)) throw new IllegalStateException("Seed requires authentication");
                    player.closeContainer();
                    SlottedInventory.clearLiveInventory(player);
                    player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 7));
                    player.getInventory().setItem(36, new ItemStack(Items.DIAMOND_BOOTS));
                    player.getInventory().setItem(40, new ItemStack(Items.SHIELD));
                    player.inventoryMenu.setCarried(new ItemStack(Items.APPLE, 3));
                    player.inventoryMenu.getCraftSlots().setItem(0, new ItemStack(Items.OAK_LOG));
                    player.inventoryMenu.getResultSlot().set(new ItemStack(Items.OAK_PLANKS, 4));
                    if (action.equals("seedoverflow")) {
                        for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
                        player.inventoryMenu.setCarried(new ItemStack(Items.DIAMOND, 7));
                    }
                    if (action.equals("seedanvil")) {
                        // Actual vanilla removed() must return these two inputs
                        // before the combat snapshot takes ownership.
                        var anvilPos = player.blockPosition().offset(1, 0, 0);
                        player.level().setBlockAndUpdate(anvilPos, Blocks.ANVIL.defaultBlockState());
                        ItemStack carried = player.inventoryMenu.getCarried();
                        player.inventoryMenu.setCarried(ItemStack.EMPTY);
                        // Opening a real menu closes the player's crafting grid.
                        player.inventoryMenu.removed(player);
                        player.containerMenu = new AnvilMenu(42, player.getInventory(),
                            ContainerLevelAccess.create(player.level(), anvilPos));
                        player.containerMenu.setCarried(carried);
                        player.containerMenu.getSlot(0).set(new ItemStack(Items.DIAMOND, 2));
                        player.containerMenu.getSlot(1).set(new ItemStack(Items.IRON_INGOT, 3));
                    }
                    player.inventoryMenu.broadcastChanges();
                }
                case "tag" -> Combatpersistence.tracker.tag(player, 300);
                case "kill" -> {
                    player.setHealth(0);
                    player.die(player.level().damageSources().generic());
                }
                case "assert" -> {
                    if (!Combatpersistence.authManager.isAuthenticated(player)) throw new IllegalStateException("Player lost authentication");
                    if (expectedDiamonds >= 0 && count(player, Items.DIAMOND) != expectedDiamonds) {
                        throw new IllegalStateException("Player diamond count " + count(player, Items.DIAMOND));
                    }
                    if (count(player, Items.OAK_PLANKS) != 0) throw new IllegalStateException("Recipe preview became owned planks");
                    if (expectedDiamonds == 7 && (count(player, Items.APPLE) != 3 || count(player, Items.OAK_LOG) != 1
                            || count(player, Items.DIAMOND_BOOTS) != 1 || count(player, Items.SHIELD) != 1)) {
                        throw new IllegalStateException("Inventory ownership did not survive restore");
                    }
                }
                case "assertpreauth" -> {
                    if (Combatpersistence.authManager.isAuthenticated(player)) throw new IllegalStateException("Replacement connection inherited authentication");
                    if (!SlottedInventory.snapshot(player).isEmpty()) throw new IllegalStateException("Inventory visible before authentication");
                    var config = Combatpersistence.config;
                    if (player.distanceToSqr(config.lobbyX, config.lobbyY, config.lobbyZ) > 0.01) {
                        throw new IllegalStateException("Real coordinates visible before authentication");
                    }
                }
                case "assertnotag" -> {
                    if (Combatpersistence.tracker.isInCombat(player)) throw new IllegalStateException("Legitimate death retained combat tag");
                }
                case "assertanvil" -> {
                    if (!Combatpersistence.authManager.isAuthenticated(player)) throw new IllegalStateException("Player lost authentication");
                    if (count(player, Items.DIAMOND) != 9 || count(player, Items.IRON_INGOT) != 3
                            || count(player, Items.APPLE) != 3 || count(player, Items.OAK_LOG) != 1
                            || count(player, Items.OAK_PLANKS) != 0 || count(player, Items.DIAMOND_BOOTS) != 1
                            || count(player, Items.SHIELD) != 1) {
                        throw new IllegalStateException("Vanilla anvil inputs were lost or duplicated during combat logout");
                    }
                }
                case "assertoverflow" -> {
                    if (!Combatpersistence.authManager.isAuthenticated(player)) throw new IllegalStateException("Player lost authentication");
                    if (count(player, Items.COBBLESTONE) != 36 * 64 || count(player, Items.DIAMOND) != 0
                            || count(player, Items.OAK_LOG) != 0 || count(player, Items.OAK_PLANKS) != 0
                            || count(player, Items.DIAMOND_BOOTS) != 1 || count(player, Items.SHIELD) != 1) {
                        throw new IllegalStateException("Full inventory restore differs from snapshot");
                    }
                    int diamonds = 0, logs = 0;
                    for (ServerLevel level : source.getServer().getAllLevels()) {
                        for (Entity entity : level.getAllEntities()) {
                            if (entity instanceof ItemEntity item) {
                                if (item.getItem().is(Items.DIAMOND)) diamonds += item.getItem().getCount();
                                if (item.getItem().is(Items.OAK_LOG)) logs += item.getItem().getCount();
                            }
                        }
                    }
                    if (diamonds != 7 || logs != 1) throw new IllegalStateException("Overflow count diamonds=" + diamonds + " logs=" + logs);
                    if (Combatpersistence.tracker.getNPCForPlayer(uuid) != null) throw new IllegalStateException("Overflow released before snapshot consumption");
                }
                case "status" -> { }
                default -> throw new IllegalStateException("Unknown action " + action);
            }
            String detail = "SECURITY_PROBE PASS " + action + " " + name
                + " auth=" + Combatpersistence.authManager.isAuthenticated(player)
                + " diamonds=" + count(player, Items.DIAMOND)
                + " apples=" + count(player, Items.APPLE)
                + " logs=" + count(player, Items.OAK_LOG)
                + " planks=" + count(player, Items.OAK_PLANKS)
                + " health=" + player.getHealth()
                + " position=" + player.position()
                + " pendingDeath=" + Combatpersistence.tracker.isPendingDeath(uuid)
                + " npc=" + Combatpersistence.tracker.getNPCForPlayer(uuid);
            source.sendSuccess(() -> Component.literal(detail), false);
            return 1;
        } catch (Exception error) {
            source.sendFailure(Component.literal("SECURITY_PROBE FAIL " + action + " " + name + ": " + error.getMessage()));
            return 0;
        }
    }

    private static int count(ServerPlayer player, Item item) {
        int total = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        ItemStack cursor = player.containerMenu.getCarried();
        if (cursor.is(item)) total += cursor.getCount();
        var crafting = player.inventoryMenu.getCraftSlots();
        for (int slot = 0; slot < crafting.getContainerSize(); slot++) {
            ItemStack stack = crafting.getItem(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }
}
