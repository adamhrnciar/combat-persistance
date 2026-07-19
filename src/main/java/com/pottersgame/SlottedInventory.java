package com.pottersgame;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.Slot;
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
 * 40 offhand). Items without a stable slot (cursor stack, transient crafting
 * grid) carry {@link #NO_SLOT} and are restored through the generic
 * add-with-drop-overflow path.
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
     * the high indices), the cursor stack, and any items in transient crafting
     * slots. Crafting slots are emptied so vanilla can't also drop them later.
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

        // Sweep items sitting in TRANSIENT slots (crafting grids and their
        // result slot), which vanilla would otherwise drop on the ground.
        // Only transient containers: matching every non-player-inventory
        // slot would also empty an open chest/barrel into the snapshot —
        // stealing the container's contents.
        for (Slot slot : player.containerMenu.slots) {
            if (slot.container instanceof TransientCraftingContainer || slot.container instanceof ResultContainer) {
                ItemStack stack = slot.getItem();
                if (!stack.isEmpty()) {
                    items.add(new SlottedStack(NO_SLOT, stack.copy()));
                    slot.set(ItemStack.EMPTY); // Clear to prevent vanilla from dropping it
                }
            }
        }
        return items;
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
            CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
            return ItemStack.CODEC
                .parse(world.registryAccess().createSerializationContext(NbtOps.INSTANCE), tag)
                .resultOrPartial(e -> Combatpersistence.LOGGER.error("Failed to decode item: {}", e))
                .orElse(null);
        } catch (Exception e) {
            Combatpersistence.LOGGER.error("Failed to parse item from Base64 NBT", e);
            return null;
        }
    }

    /** Encodes a snapshot for persistence, skipping (and error-logging) items that fail to encode. */
    public static List<SlotItem> encodeAll(ServerLevel world, List<SlottedStack> items) {
        List<SlotItem> encoded = new ArrayList<>();
        for (SlottedStack item : items) {
            String b64 = encode(world, item.stack());
            if (b64 != null) {
                encoded.add(new SlotItem(item.slot(), b64));
            }
        }
        return encoded;
    }

    /** The decodable items plus how many entries failed to decode. */
    public record DecodeResult(List<SlottedStack> items, int failures) {}

    public static DecodeResult decodeAll(ServerLevel world, List<SlotItem> encoded) {
        List<SlottedStack> items = new ArrayList<>();
        int failures = 0;
        for (SlotItem item : encoded) {
            ItemStack stack = decode(world, item.nbt);
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
    public record PlaceResult(int exact, int equipped, int loose, int dropped) {}

    /**
     * Hands items back to the player. An item whose original slot is valid and
     * empty goes to that exact slot (setItem also writes armor/offhand for
     * indices >= {@link #MAIN_SIZE}); everything else goes through add() with a
     * drop-on-ground overflow fallback. With clearFirst the live inventory and
     * cursor are wiped first, making the given items the single authoritative
     * copy — callers must only pass clearFirst when every stored item decoded.
     */
    public static PlaceResult place(ServerPlayer player, List<SlottedStack> items, boolean clearFirst) {
        if (clearFirst) {
            player.getInventory().clearContent();
            player.containerMenu.setCarried(ItemStack.EMPTY);
        }
        int exact = 0, equipped = 0, loose = 0, dropped = 0;
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
                player.drop(stack, false);
                dropped++;
            }
        }
        player.containerMenu.sendAllDataToRemote();
        return new PlaceResult(exact, equipped, loose, dropped);
    }
}
