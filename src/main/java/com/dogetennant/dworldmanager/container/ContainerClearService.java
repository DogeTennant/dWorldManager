package com.dogetennant.dworldmanager.container;

import com.dogetennant.dworldmanager.DWorldManager;
import com.dogetennant.dworldmanager.util.ChunkWalker;
import com.dogetennant.dworldmanager.util.Msg;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Allay;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Sweeps a world for tainted containers/entities and clears their contents,
 * plus a couple of vectors that don't need tainting at all: allays (there's
 * no legitimate reason for one to be holding an item worth preserving) and
 * dropped item entities sitting on the ground.
 *
 * Blocks/entities themselves are never removed - only their contents. A
 * chest a player once used as storage stays part of their build; it's just
 * empty afterward.
 */
public class ContainerClearService {

    private final DWorldManager plugin;
    private final ContainerTaintService taintService;

    public ContainerClearService(DWorldManager plugin, ContainerTaintService taintService) {
        this.plugin = plugin;
        this.taintService = taintService;
    }

    public interface ClearCallback {
        void onComplete(ClearStats stats);
        void onError(String message);
        default void onProgress(int scanned, int total) {
        }
    }

    public void clearTaintedInWorld(World world, ClearCallback callback) {
        double maxBorder = plugin.getConfigManager().getMaxScanBorderSize();
        List<int[]> chunkCoords = ChunkWalker.planChunks(world, maxBorder);
        if (chunkCoords == null) {
            callback.onError(Msg.text(plugin, "border-too-big",
                    "&cWorld border for '%world%' is larger than the configured safety cap "
                            + "(blocks.max-scan-border-size: %max%). Set a real border on this world first.",
                    "world", world.getName(), "max", String.valueOf((long) maxBorder)));
            return;
        }

        ClearStats stats = new ClearStats();
        long tickBudgetMillis = plugin.getConfigManager().getScanTickBudgetMillis();

        boolean debug = plugin.getConfigManager().isDebug();

        ChunkWalker.walk(plugin, world, chunkCoords, tickBudgetMillis,
                chunk -> {
                    for (BlockState state : chunk.getTileEntities()) {
                        boolean isHolder = state instanceof InventoryHolder;
                        boolean tainted = isHolder && taintService.isTainted(state);
                        if (debug && isHolder) {
                            plugin.getLogger().info("[clear-debug] tile entity " + state.getType()
                                    + " at " + state.getX() + "," + state.getY() + "," + state.getZ()
                                    + " tainted=" + tainted);
                        }
                        if (tainted) {
                            clearContainerBlock(state, debug);
                            stats.containersCleared++;
                        }
                    }
                    for (Entity entity : chunk.getEntities()) {
                        clearEntityIfNeeded(entity, stats);
                    }
                },
                callback::onProgress,
                () -> callback.onComplete(stats));
    }

    /**
     * Clears the container's inventory directly - no BlockState.update() call.
     * Container#getInventory() is backed by the live tile entity (same as it is
     * for minecart/boat storage entities, which need no update() at all and just
     * work), so calling update() afterward doesn't persist the clear - it
     * overwrites the real, now-empty container with whatever inventory contents
     * were captured in the snapshot at the moment it was taken, i.e. the OLD,
     * pre-clear contents. That silently undid every previous clear attempt.
     */
    private void clearContainerBlock(BlockState state, boolean debug) {
        if (!(state instanceof InventoryHolder holder)) return;

        Inventory inventory = holder.getInventory();
        int itemsBefore = countNonEmpty(inventory);
        inventory.clear();

        if (debug) {
            plugin.getLogger().info("[clear-debug] cleared " + state.getType()
                    + " at " + state.getWorld().getName() + " " + state.getX() + "," + state.getY() + "," + state.getZ()
                    + " itemsBefore=" + itemsBefore);
        }
    }

    private int countNonEmpty(Inventory inventory) {
        int count = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item != null && !item.getType().isAir()) count++;
        }
        return count;
    }

    private void clearEntityIfNeeded(Entity entity, ClearStats stats) {
        if (entity instanceof ItemFrame frame) {
            if (taintService.isTainted(frame) && !frame.getItem().getType().isAir()) {
                frame.setItem(null);
                stats.itemFramesCleared++;
            }
        } else if (entity instanceof ArmorStand stand) {
            if (taintService.isTainted(stand)) {
                clearArmorStandEquipment(stand);
                stats.armorStandsCleared++;
            }
        } else if (entity instanceof Allay allay) {
            // No legitimate reason an allay should be holding something worth preserving - always clear.
            allay.getInventory().clear();
            stats.allaysCleared++;
        } else if (entity instanceof InventoryHolder holder && taintService.isTainted(entity)) {
            holder.getInventory().clear();
            stats.containersCleared++;
        } else if (entity instanceof Item item) {
            item.remove();
            stats.droppedItemsRemoved++;
        }
    }

    private void clearArmorStandEquipment(ArmorStand stand) {
        EntityEquipment equipment = stand.getEquipment();
        if (equipment == null) return;
        equipment.setHelmet(null);
        equipment.setChestplate(null);
        equipment.setLeggings(null);
        equipment.setBoots(null);
        equipment.setItemInMainHand(null);
        equipment.setItemInOffHand(null);
    }
}
