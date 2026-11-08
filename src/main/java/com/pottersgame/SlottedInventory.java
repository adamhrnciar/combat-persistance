package com.pottersgame;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Slot-aware inventory snapshot/encode/decode/place, shared by the combat-log
 * tracker and the pre-auth stash. Slots are vanilla Inventory container
 * indices: 0-35 main storage, then equipment (36-39 armor feet->head,
 * 40 offhand). Items without a stable slot (cursor stack and player-owned
 * menu inputs) carry {@link #NO_SLOT} and are restored through the generic
 * add-with-deferred-overflow path.
 */
public final class SlottedInventory {
    public static final int NO_SLOT = -1;
    /** Size of the main storage (36); container indices at or above this are equipment. */
    public static final int MAIN_SIZE = net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE;
    public static final int OFFHAND_SLOT = net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND;

    private SlottedInventory() {}

    /** A live item with the container slot it came from ({@link #NO_SLOT} for cursor/crafting). */
    public record SlottedStack(int slot, ItemStack stack) {}

    /** Gson persistence unit: one Base64-encoded item plus its slot. */
    public static class SlotItem {
        public int slot;
        public String nbt;

        // Gson honors a declared no-arg constructor, so entries missing the
        // "slot" field (legacy or hand-edited JSON) default to slotless —
        // defaulting to 0 would overwrite hotbar slot 0 on restore.
        public SlotItem() {
            this.slot = NO_SLOT;
        }

        public SlotItem(int slot, String nbt) {
            this.slot = slot;
            this.nbt = nbt;
        }
    }

    /**
     * Copies the player's full container (main storage plus armor/offhand via
     * the high indices), the cursor stack, and player-owned menu inputs.
     * This method never mutates the live copy; clear it only after the
     * complete snapshot has been encoded and durably persisted.
     */
    public static List<SlottedStack> snapshot(ServerPlayer player) {
        List<SlottedStack> items = new ArrayList<>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                items.add(new SlottedStack(i, stack.copy()));
            }
        }

        // Include the item currently held by the cursor (ScreenHandler stack)
        ItemStack cursorStack = player.containerMenu.getCarried().copy();
        if (!cursorStack.isEmpty()) {
            items.add(new SlottedStack(NO_SLOT, cursorStack));
        }

        // Vanilla returns these inputs on menu close. On disconnect it can
        // drop them into the world instead, so capture them before closing.
        // Results are previews; saving inputs and results would duplicate output.
        for (Slot slot : playerOwnedInputSlots(player)) {
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty()) {
                items.add(new SlottedStack(NO_SLOT, stack.copy()));
            }
        }
        return items;
    }

    /** Clear only player-owned inventory state after its durable handoff. */
    public static void clearLiveInventory(ServerPlayer player) {
        player.getInventory().clearContent();
        player.containerMenu.setCarried(ItemStack.EMPTY);
        for (Slot slot : playerOwnedInputSlots(player)) {
            // Early login has no packet listener. Updating inputs can calculate
            // recipes and send packets, so never trigger slot-change callbacks.
            slot.container.removeItemNoUpdate(slot.getContainerSlot());
        }
        clearResultPreviews(player.containerMenu);
        if (player.inventoryMenu != null && player.inventoryMenu != player.containerMenu) {
            clearResultPreviews(player.inventoryMenu);
        }
    }

    private static List<Slot> playerOwnedInputSlots(ServerPlayer player) {
        List<Slot> inputs = new ArrayList<>();
        addPlayerOwnedInputSlots(player.containerMenu, inputs);
        if (player.inventoryMenu != null && player.inventoryMenu != player.containerMenu) {
            addPlayerOwnedInputSlots(player.inventoryMenu, inputs);
        }
        return inputs;
    }

    private static void addPlayerOwnedInputSlots(AbstractContainerMenu menu, List<Slot> inputs) {
        int inputCount = playerOwnedInputCount(menu);
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            boolean owned = slot.container instanceof TransientCraftingContainer || i < inputCount;
            if (menu instanceof LoomMenu loom) {
                owned |= slot == loom.getBannerSlot() || slot == loom.getDyeSlot() || slot == loom.getPatternSlot();
            }
            // A crafting input can be exposed through both menus. Capture its
            // container index once, even when the menus use different Slot objects.
            if (owned && inputs.stream().noneMatch(existing -> existing.container == slot.container
                    && existing.getContainerSlot() == slot.getContainerSlot())) {
                inputs.add(slot);
            }
        }
    }

    private static int playerOwnedInputCount(AbstractContainerMenu menu) {
        // ItemCombinerMenu constructs contiguous input slots before its result.
        // The remaining counts follow vanilla 26.3 menu constructors. Never
        // treat every non-inventory container as player-owned: chests, horses,
        // furnaces and block crafters keep their contents in the world.
        if (menu instanceof ItemCombinerMenu combiner) return combiner.getResultSlot();
        if (menu instanceof MerchantMenu || menu instanceof GrindstoneMenu
                || menu instanceof CartographyTableMenu || menu instanceof EnchantmentMenu) return 2;
        if (menu instanceof StonecutterMenu || menu instanceof BeaconMenu) return 1;
        return 0;
    }

    private static void clearResultPreviews(AbstractContainerMenu menu) {
        int resultIndex = -1;
        if (menu instanceof ItemCombinerMenu combiner) resultIndex = combiner.getResultSlot();
        else if (menu instanceof MerchantMenu) resultIndex = 2;
        else if (menu instanceof StonecutterMenu) resultIndex = StonecutterMenu.RESULT_SLOT;
        else if (menu instanceof GrindstoneMenu) resultIndex = GrindstoneMenu.RESULT_SLOT;
        else if (menu instanceof CartographyTableMenu) resultIndex = CartographyTableMenu.RESULT_SLOT;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container instanceof ResultContainer || i == resultIndex
                    || menu instanceof LoomMenu loom && slot == loom.getResultSlot()) {
                slot.container.removeItemNoUpdate(slot.getContainerSlot());
            }
        }
    }

    /** Base64 of the compressed item NBT, or null (with an error logged) on failure. */
    public static String encode(ServerLevel world, ItemStack stack) {
        try {
            CompoundTag tag = (CompoundTag) ItemStack.CODEC
                .encodeStart(world.registryAccess().createSerializationContext(NbtOps.INSTANCE), stack)
                .getOrThrow(e -> new RuntimeException("Failed to encode item: " + e));
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            Combatpersistence.LOGGER.error("Failed to encode item to Base64 NBT", e);
            return null;
        }
    }

    /** Inverse of {@link #encode}; null (with an error logged) on failure. */
    public static ItemStack decode(ServerLevel world, String b64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(b64);
            CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.create(16L * 1024 * 1024));
            return ItemStack.CODEC
                .parse(world.registryAccess().createSerializationContext(NbtOps.INSTANCE), tag)
                .result()
                .orElse(null);
        } catch (Exception e) {
            Combatpersistence.LOGGER.error("Failed to parse item from Base64 NBT", e);
            return null;
        }
    }

    /** Encode the entire snapshot or fail without destroying any live items. */
    public static List<SlotItem> encodeAll(ServerLevel world, List<SlottedStack> items) {
        List<SlotItem> encoded = new ArrayList<>();
        for (SlottedStack item : items) {
            String b64 = encode(world, item.stack());
            if (b64 == null) throw new IllegalStateException("Inventory snapshot contains an item that cannot be encoded");
            encoded.add(new SlotItem(item.slot(), b64));
        }
        return encoded;
    }

    /** The decodable items plus how many entries failed to decode. */
    public record DecodeResult(List<SlottedStack> items, int failures) {}

    public static DecodeResult decodeAll(ServerLevel world, List<SlotItem> encoded) {
        List<SlottedStack> items = new ArrayList<>();
        int failures = 0;
        for (SlotItem item : encoded) {
            ItemStack stack = item == null ? null : decode(world, item.nbt);
            // Only non-empty stacks are ever encoded, so an empty result is as
            // wrong as a null one — count it so callers keep the safe path.
            if (stack == null || stack.isEmpty()) {
                failures++;
            } else {
                items.add(new SlottedStack(item.slot, stack));
            }
        }
        return new DecodeResult(items, failures);
    }

    /** Outcome counts of {@link #place}. exact = placed in its original slot
     *  (equipped counts the subset that went to armor/offhand), loose = added
     *  wherever the inventory had room, dropped = overflow spilled on the ground. */
    public record PlaceResult(int exact, int equipped, int loose, List<ItemStack> overflow) {
        public int dropped() { return overflow.size(); }

        /** Release overflow only after the source snapshot is durably consumed. */
        public void dropOverflow(ServerPlayer player) {
            for (ItemStack stack : overflow) player.drop(stack, false);
            overflow.clear();
        }
    }

    /**
     * Hands items back to the player. An item whose original slot is valid and
     * empty goes to that exact slot (setItem also writes armor/offhand for
     * indices >= {@link #MAIN_SIZE}); everything else goes through add() with a
     * deferred overflow fallback. With clearFirst the live inventory and
     * cursor are wiped first, making the given items the single authoritative
     * copy — callers must only pass clearFirst when every stored item decoded.
     */
    public static PlaceResult place(ServerPlayer player, List<SlottedStack> items, boolean clearFirst) {
        if (clearFirst) {
            clearLiveInventory(player);
        }
        int exact = 0, equipped = 0, loose = 0;
        List<ItemStack> overflow = new ArrayList<>();
        for (SlottedStack item : items) {
            int slot = item.slot();
            ItemStack stack = item.stack();
            // Bound check is load-bearing: Inventory.setItem silently discards
            // out-of-range indices (corrupt or hand-edited slot values).
            if (slot >= 0 && slot < player.getInventory().getContainerSize()
                    && player.getInventory().getItem(slot).isEmpty()) {
                player.getInventory().setItem(slot, stack);
                exact++;
                if (slot >= MAIN_SIZE) equipped++;
            } else if (player.getInventory().add(stack)) {
                loose++;
            } else {
                overflow.add(stack);
            }
        }
        player.containerMenu.sendAllDataToRemote();
        return new PlaceResult(exact, equipped, loose, overflow);
    }
}
