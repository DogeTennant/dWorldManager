package com.dogetennant.dworldmanager.container;

import com.dogetennant.dworldmanager.DWorldManager;
import org.bukkit.block.BlockState;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * Taints a container/entity the moment a player actually deposits an item
 * into it - never on mere viewing, so idly opening a dungeon/village loot
 * chest doesn't destroy it. Insertion is what makes a container suspect as
 * player storage, not visibility.
 */
public class ContainerTaintListener implements Listener {

    private final DWorldManager plugin;
    private final ContainerTaintService taintService;

    public ContainerTaintListener(DWorldManager plugin, ContainerTaintService taintService) {
        this.plugin = plugin;
        this.taintService = taintService;
    }

    /** Any container block a player places is immediately treated as player storage - it may already be filled (a placed shulker box carries its contents with it). */
    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        BlockState state = event.getBlockPlaced().getState();
        if (state instanceof InventoryHolder) {
            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[taint-debug] onBlockPlace: " + state.getType() + " at "
                        + state.getX() + "," + state.getY() + "," + state.getZ());
            }
            taintService.taint(state);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        Inventory clicked = event.getClickedInventory();
        if (clicked == null) return;

        boolean depositsIntoTop = switch (event.getAction()) {
            case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR, HOTBAR_SWAP, HOTBAR_MOVE_AND_READD ->
                    clicked.equals(top);
            case MOVE_TO_OTHER_INVENTORY -> !clicked.equals(top);
            default -> false;
        };

        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[taint-debug] onInventoryClick: action=" + event.getAction()
                    + " clicked=" + (clicked.equals(top) ? "top" : "bottom") + " depositsIntoTop=" + depositsIntoTop);
        }

        if (depositsIntoTop) {
            taintService.taintHolderOf(top);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        int topSize = top.getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize) {
                taintService.taintHolderOf(top);
                return;
            }
        }
    }

    /** Hoppers/droppers feeding a container should taint it too, or players could bypass tainting by never manually opening it. */
    @EventHandler(ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        taintService.taintHolderOf(event.getDestination());
    }

    /** Item frames (regular and glow) don't use a normal inventory GUI - insertion happens straight through the interact event. */
    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof ItemFrame frame)) return;
        if (!frame.getItem().getType().isAir()) return; // already holding something - this click just rotates it

        ItemStack hand = event.getHand() == EquipmentSlot.HAND
                ? event.getPlayer().getInventory().getItemInMainHand()
                : event.getPlayer().getInventory().getItemInOffHand();
        if (!hand.getType().isAir()) {
            taintService.taint(frame);
        }
    }

    /** Armor stands equip through a dedicated event rather than a normal inventory GUI. */
    @EventHandler(ignoreCancelled = true)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        ItemStack given = event.getPlayerItem();
        if (given != null && !given.getType().isAir()) {
            taintService.taint(event.getRightClicked());
        }
    }
}
