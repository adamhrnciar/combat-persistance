package com.pottersgame;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SlottedInventoryTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // This test exercises ownership, not data-pack item defaults. Minecraft
        // 26.3 binds those defaults later during world loading.
        java.util.stream.Stream.of(Items.DIAMOND, Items.DIAMOND_BOOTS, Items.SHIELD,
            Items.APPLE, Items.OAK_LOG, Items.OAK_PLANKS, Items.EMERALD).forEach(item -> {
                var holder = item.builtInRegistryHolder();
                if (!holder.areComponentsBound()) holder.bindComponents(net.minecraft.core.component.DataComponentMap.EMPTY);
            });
    }

    @Test void snapshotKeepsRealAnvilInputsButNeverItsPreview() {
        ServerPlayer player = mock(ServerPlayer.class);
        Inventory inventory = new Inventory(player, new EntityEquipment());
        when(player.getInventory()).thenReturn(inventory);
        setInventoryMenu(player, inventory);
        inventory.setItem(0, new ItemStack(Items.DIAMOND, 2));
        AnvilMenu anvil = new AnvilMenu(1, inventory);
        player.containerMenu = anvil;
        // Populate backing storage without running recipe calculation. That
        // calculation needs loaded data packs, while this test checks ownership.
        Slot firstInput = anvil.getSlot(AnvilMenu.INPUT_SLOT);
        Slot secondInput = anvil.getSlot(AnvilMenu.ADDITIONAL_SLOT);
        ((SimpleContainer) firstInput.container).getItems().set(firstInput.getContainerSlot(),
            new ItemStack(Items.DIAMOND_BOOTS));
        ((SimpleContainer) secondInput.container).getItems().set(secondInput.getContainerSlot(),
            new ItemStack(Items.EMERALD, 3));
        Slot preview = anvil.getSlot(AnvilMenu.RESULT_SLOT);
        preview.container.setItem(preview.getContainerSlot(), new ItemStack(Items.OAK_PLANKS, 4));
        anvil.setCarried(new ItemStack(Items.APPLE, 3));

        var snapshot = SlottedInventory.snapshot(player);
        assertEquals(4, snapshot.size());
        assertTrue(snapshot.stream().anyMatch(item -> item.slot() == 0 && item.stack().is(Items.DIAMOND)));
        assertTrue(snapshot.stream().anyMatch(item -> item.slot() == SlottedInventory.NO_SLOT
            && item.stack().is(Items.DIAMOND_BOOTS)));
        assertTrue(snapshot.stream().anyMatch(item -> item.slot() == SlottedInventory.NO_SLOT
            && item.stack().is(Items.EMERALD) && item.stack().getCount() == 3));
        assertTrue(snapshot.stream().anyMatch(item -> item.stack().is(Items.APPLE) && item.stack().getCount() == 3));
        assertFalse(snapshot.stream().anyMatch(item -> item.stack().is(Items.OAK_PLANKS)));
        assertEquals(1, firstInput.getItem().getCount());
        assertEquals(3, secondInput.getItem().getCount());
        assertEquals(4, preview.getItem().getCount());
        assertNull(player.connection, "The handoff also runs before a connection exists");

        SlottedInventory.clearLiveInventory(player);
        assertTrue(inventory.isEmpty());
        assertTrue(firstInput.getItem().isEmpty());
        assertTrue(secondInput.getItem().isEmpty());
        assertTrue(preview.getItem().isEmpty());
        assertTrue(anvil.getCarried().isEmpty());
        // Closing after the handoff cannot release a second copy of the inputs.
        anvil.removed(player);
        verify(player, never()).drop(any(ItemStack.class), anyBoolean());
        assertEquals(3, snapshot.stream().filter(item -> item.stack().is(Items.EMERALD))
            .findFirst().orElseThrow().stack().getCount());
    }

    @Test void snapshotDoesNotTakeItemsFromRealChestMenu() {
        ServerPlayer player = mock(ServerPlayer.class);
        Inventory inventory = new Inventory(player, new EntityEquipment());
        when(player.getInventory()).thenReturn(inventory);
        setInventoryMenu(player, inventory);
        inventory.setItem(0, new ItemStack(Items.DIAMOND, 2));
        SimpleContainer chest = new SimpleContainer(27);
        chest.setItem(0, new ItemStack(Items.EMERALD));
        ChestMenu menu = ChestMenu.threeRows(2, inventory, chest);
        player.containerMenu = menu;
        menu.setCarried(new ItemStack(Items.APPLE, 3));

        var snapshot = SlottedInventory.snapshot(player);
        assertEquals(2, snapshot.size());
        assertFalse(snapshot.stream().anyMatch(item -> item.stack().is(Items.EMERALD)));
        SlottedInventory.clearLiveInventory(player);
        assertTrue(inventory.isEmpty());
        assertTrue(menu.getCarried().isEmpty());
        assertEquals(1, chest.getItem(0).getCount(), "Shared chest storage stays in the world");
    }

    @Test void snapshotKeepsIngredientsButExcludesRecipePreviewAndChestContents() {
        ServerPlayer player = mock(ServerPlayer.class);
        Inventory inventory = mock(Inventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getContainerSize()).thenReturn(41);
        when(inventory.getItem(anyInt())).thenReturn(ItemStack.EMPTY);
        when(inventory.getItem(0)).thenReturn(new ItemStack(Items.DIAMOND, 2));
        when(inventory.getItem(36)).thenReturn(new ItemStack(Items.DIAMOND_BOOTS));
        when(inventory.getItem(40)).thenReturn(new ItemStack(Items.SHIELD));
        setInventoryMenu(player, inventory);
        TestMenu menu = new TestMenu();
        player.containerMenu = menu;
        menu.setCarried(new ItemStack(Items.APPLE, 3));
        TransientCraftingContainer crafting = new TransientCraftingContainer(menu, 2, 2);
        crafting.setItem(0, new ItemStack(Items.OAK_LOG));
        ResultContainer result = new ResultContainer();
        result.setItem(0, new ItemStack(Items.OAK_PLANKS, 4));
        SimpleContainer chest = new SimpleContainer(new ItemStack(Items.EMERALD, 5));
        menu.add(new Slot(crafting, 0, 0, 0));
        menu.add(new Slot(result, 0, 0, 0));
        menu.add(new Slot(chest, 0, 0, 0));

        var snapshot = SlottedInventory.snapshot(player);
        assertEquals(5, snapshot.size());
        assertTrue(snapshot.stream().anyMatch(item -> item.slot() == 36 && item.stack().is(Items.DIAMOND_BOOTS)));
        assertTrue(snapshot.stream().anyMatch(item -> item.slot() == 40 && item.stack().is(Items.SHIELD)));
        assertEquals(1, snapshot.stream().filter(item -> item.stack().is(Items.OAK_LOG)).count());
        assertFalse(snapshot.stream().anyMatch(item -> item.stack().is(Items.OAK_PLANKS) || item.stack().is(Items.EMERALD)));
        // Taking a backup must not destroy the only live copy if the disk fails.
        assertEquals(1, crafting.getItem(0).getCount());
        assertEquals(4, result.getItem(0).getCount());
        assertEquals(3, menu.getCarried().getCount());
        verify(inventory, never()).clearContent();

        // Early join has no packet listener. Crafting recalculation would try
        // to send a result packet and reject every connection before play.
        menu.rejectCraftingUpdates = true;
        SlottedInventory.clearLiveInventory(player);
        verify(inventory).clearContent();
        assertTrue(crafting.getItem(0).isEmpty());
        assertTrue(result.getItem(0).isEmpty());
        assertTrue(menu.getCarried().isEmpty());
        assertEquals(5, chest.getItem(0).getCount());
        assertEquals(1, snapshot.stream().filter(item -> item.stack().is(Items.OAK_LOG)).findFirst().orElseThrow().stack().getCount());
    }

    private static void setInventoryMenu(ServerPlayer player, Inventory inventory) {
        try {
            java.lang.reflect.Field field = Player.class.getDeclaredField("inventoryMenu");
            field.setAccessible(true);
            field.set(player, new InventoryMenu(inventory, false, player));
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Could not initialize the player's canonical menu", error);
        }
    }

    private static class TestMenu extends AbstractContainerMenu {
        boolean rejectCraftingUpdates;
        TestMenu() { super(null, 0); }
        void add(Slot slot) { addSlot(slot); }
        @Override public void slotsChanged(net.minecraft.world.Container container) {
            if (rejectCraftingUpdates) throw new IllegalStateException("No connection during early join");
        }
        @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
        @Override public boolean stillValid(Player player) { return true; }
    }
}
