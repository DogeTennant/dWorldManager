package com.dogetennant.dworldmanager.container;

import com.dogetennant.dworldmanager.DWorldManager;
import io.papermc.paper.event.entity.ItemTransportingEntityValidateTargetEvent;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.ChiseledBookshelf;
import org.bukkit.block.DecoratedPot;
import org.bukkit.block.Jukebox;
import org.bukkit.block.Lectern;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseArmorEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
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
            // the bundle actions: a bundle on the cursor emptied into a slot, or items put into a
            // bundle that lies in the slot
            case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR, HOTBAR_SWAP, HOTBAR_MOVE_AND_READD,
                 PLACE_FROM_BUNDLE, PLACE_ALL_INTO_BUNDLE, PLACE_SOME_INTO_BUNDLE ->
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

    /**
     * Decorated pots, chiseled bookshelves, lecterns and jukeboxes take an item by right-clicking
     * the block, not through an inventory screen. Chests and the like only open on a right-click,
     * which is viewing and does not taint.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.useInteractedBlock() == Event.Result.DENY) return;
        ItemStack hand = event.getItem();
        Block block = event.getClickedBlock();
        if (hand == null || hand.isEmpty() || block == null) return;

        BlockState state = block.getState();
        if (state instanceof DecoratedPot || state instanceof ChiseledBookshelf
                || state instanceof Lectern || state instanceof Jukebox) {
            taintService.taint(state);
        }
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

    /** A dispenser facing an armor stand puts armour on it - the same as a player dressing it. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDispenseArmor(BlockDispenseArmorEvent event) {
        if (event.getTargetEntity() instanceof ArmorStand stand) {
            taintService.taint(stand);
        }
    }

    /**
     * Copper golems carry items from a copper chest into nearby chests, and the server fires no
     * InventoryMoveItemEvent for it - only this check, before the golem walks to a chest. A golem
     * with an item in its hand is about to deliver there.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemTransportTarget(ItemTransportingEntityValidateTargetEvent event) {
        if (!event.isAllowed()) return;
        if (!(event.getEntity() instanceof LivingEntity carrier) || carrier.getEquipment() == null) return;
        if (carrier.getEquipment().getItemInMainHand().isEmpty()) return; // fetching from a copper chest
        taintService.taint(event.getBlock().getState());
    }
}
