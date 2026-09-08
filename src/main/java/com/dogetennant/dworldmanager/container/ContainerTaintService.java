package com.dogetennant.dworldmanager.container;

import com.dogetennant.dworldmanager.DWorldManager;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.TileState;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.persistence.PersistentDataType;

/**
 * Marks containers and storage-capable entities as "tainted" the moment a
 * player deposits an item into them, using a PersistentDataContainer flag
 * stamped directly on the block or entity - no database involved.
 *
 * A container/entity that's never been tainted is assumed to hold only
 * vanilla-generated loot (or nothing at all) and is left alone during a
 * wipe. One that has been touched gets its contents cleared, whether or not
 * it still contains anything at wipe time.
 */
public class ContainerTaintService {

    private final DWorldManager plugin;
    private final NamespacedKey taintKey;

    public ContainerTaintService(DWorldManager plugin) {
        this.plugin = plugin;
        this.taintKey = new NamespacedKey(plugin, "tainted");
    }

    public void taint(BlockState state) {
        if (!(state instanceof TileState) || isTainted(state)) return;

        // Re-fetch a fresh snapshot straight from the block rather than trusting
        // whatever BlockState we were handed (e.g. an inventory holder captured at
        // click time) - this guarantees update() is writing to a live, correctly
        // linked tile entity instead of a possibly-stale detached copy.
        Block block = state.getBlock();
        BlockState fresh = block.getState();
        if (!(fresh instanceof TileState freshTile)) return;

        freshTile.getPersistentDataContainer().set(taintKey, PersistentDataType.BYTE, (byte) 1);
        boolean applied = freshTile.update(true, false);

        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[taint-debug] tainted block " + block.getType()
                    + " at " + block.getWorld().getName() + " " + block.getX() + "," + block.getY() + "," + block.getZ()
                    + " (update applied=" + applied + ")");
        }
    }

    public boolean isTainted(BlockState state) {
        return state instanceof TileState tile
                && tile.getPersistentDataContainer().has(taintKey, PersistentDataType.BYTE);
    }

    public void taint(Entity entity) {
        if (isTainted(entity)) return;
        entity.getPersistentDataContainer().set(taintKey, PersistentDataType.BYTE, (byte) 1);

        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[taint-debug] tainted entity " + entity.getType()
                    + " at " + entity.getLocation());
        }
    }

    public boolean isTainted(Entity entity) {
        return entity.getPersistentDataContainer().has(taintKey, PersistentDataType.BYTE);
    }

    /**
     * Taints whichever block(s) or entity actually back this inventory. Safe to
     * call for any inventory, including ones with no persistent holder (crafting
     * grids, anvils, a player's own inventory) - those are silently ignored.
     */
    public void taintHolderOf(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();

        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[taint-debug] taintHolderOf called, holder="
                    + (holder == null ? "null" : holder.getClass().getName()));
        }

        if (holder instanceof DoubleChest doubleChest) {
            if (doubleChest.getLeftSide() instanceof BlockState left) taint(left);
            if (doubleChest.getRightSide() instanceof BlockState right) taint(right);
        } else if (holder instanceof BlockState state) {
            taint(state);
        } else if (holder instanceof Entity entity) {
            taint(entity);
        }
        // Player, null, or other non-persistent holders - nothing to taint
    }
}
