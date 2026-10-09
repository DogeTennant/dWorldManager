package com.dogetennant.dworldmanager.container;

import com.dogetennant.dworldmanager.DWorldManager;
import com.dogetennant.dworldmanager.Fakes;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.ChiseledBookshelf;
import org.bukkit.block.DecoratedPot;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which actions mark a container as player storage (cleared at the wipe). Putting something in
 * must always mark it; only looking or taking out must not (loot chests stay as they are).
 */
class ContainerTaintListenerTest {

    private final DWorldManager plugin = Fakes.plugin();
    private final ContainerTaintService taint = mock(ContainerTaintService.class);
    private final ContainerTaintListener listener = new ContainerTaintListener(plugin, taint);
    private final Inventory chest = mock(Inventory.class);
    private final Inventory own = mock(Inventory.class);

    private void click(InventoryAction action, Inventory clicked) {
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(chest);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getView()).thenReturn(view);
        when(event.getClickedInventory()).thenReturn(clicked);
        when(event.getAction()).thenReturn(action);
        Fakes.fire(listener, event);
    }

    private static ItemStack item(Material type) {
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(type);
        return item;
    }

    /** Right-clicks a block with {@code hand} in the main hand. */
    private void rightClick(BlockState state, ItemStack hand) {
        Material type = state.getType();
        Block block = mock(Block.class);
        when(block.getState()).thenReturn(state);
        when(block.getType()).thenReturn(type);
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
        when(event.getClickedBlock()).thenReturn(block);
        when(event.getItem()).thenReturn(hand);
        when(event.useInteractedBlock()).thenReturn(Event.Result.DEFAULT);
        Fakes.fire(listener, event);
    }

    @Test
    void placingItemsIntoTheChestMarksIt() {
        click(InventoryAction.PLACE_ALL, chest);
        click(InventoryAction.MOVE_TO_OTHER_INVENTORY, own);     // shift-click from the player's inventory

        verify(taint, times(2)).taintHolderOf(chest);
    }

    @Test
    void lookingAndTakingOutDoNotMarkIt() {
        click(InventoryAction.PICKUP_ALL, chest);
        click(InventoryAction.MOVE_TO_OTHER_INVENTORY, chest);   // shift-click out of the chest
        click(InventoryAction.PLACE_ALL, own);                   // placing into the player's own inventory
        click(InventoryAction.PICKUP_FROM_BUNDLE, chest);        // a bundle on the cursor takes an item out

        verify(taint, never()).taintHolderOf(any());
    }

    @Test
    void emptyingABundleIntoTheChestMarksIt() {
        // a full bundle on the cursor, clicked on an empty chest slot, puts its items into the chest
        click(InventoryAction.PLACE_FROM_BUNDLE, chest);

        verify(taint).taintHolderOf(chest);
    }

    @Test
    void fillingABundleThatLiesInTheChestMarksIt() {
        click(InventoryAction.PLACE_ALL_INTO_BUNDLE, chest);
        click(InventoryAction.PLACE_SOME_INTO_BUNDLE, chest);

        verify(taint, times(2)).taintHolderOf(chest);
    }

    @Test
    void puttingAnItemIntoADecoratedPotMarksIt() {
        DecoratedPot pot = mock(DecoratedPot.class);
        when(pot.getType()).thenReturn(Material.DECORATED_POT);

        rightClick(pot, item(Material.DIAMOND));

        verify(taint).taint(pot);
    }

    @Test
    void puttingABookIntoAChiseledBookshelfMarksIt() {
        ChiseledBookshelf shelf = mock(ChiseledBookshelf.class);
        when(shelf.getType()).thenReturn(Material.CHISELED_BOOKSHELF);

        rightClick(shelf, item(Material.ENCHANTED_BOOK));

        verify(taint).taint(shelf);
    }

    @Test
    void openingAChestWithSomethingInHandDoesNotMarkIt() {
        Chest lootChest = mock(Chest.class);
        when(lootChest.getType()).thenReturn(Material.CHEST);

        rightClick(lootChest, item(Material.DIAMOND));

        verify(taint, never()).taint(any(BlockState.class));
    }

    @Test
    void rightClickingAPotWithAnEmptyHandDoesNotMarkIt() {
        DecoratedPot pot = mock(DecoratedPot.class);
        when(pot.getType()).thenReturn(Material.DECORATED_POT);

        rightClick(pot, null);

        verify(taint, never()).taint(any(BlockState.class));
    }
}
