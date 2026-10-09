package com.dogetennant.dworldmanager.block;

import com.dogetennant.dworldmanager.DWorldManager;
import org.bukkit.Material;
import org.bukkit.block.Block;

import java.util.HashSet;
import java.util.Set;

/**
 * Tracks which coordinates had a restricted-material block placed by a
 * player, so a freeze scan can optionally skip naturally-generated instances
 * (e.g. freeze player-placed diamond ore used to hide value, but leave
 * natural ore veins alone).
 *
 * Only materials in blocks.restricted-materials are tracked - not every
 * block placement in the world - so this stays bounded the same way
 * frozen_blocks does, and shrinks again as tracked blocks get broken.
 */
public class PlacedBlockTracker {

    private final DWorldManager plugin;

    public PlacedBlockTracker(DWorldManager plugin) {
        this.plugin = plugin;
    }

    private boolean isTrackedMaterial(Material material) {
        return plugin.getConfigManager().getRestrictedMaterials().contains(material);
    }

    /** Records a placed restricted block; the write runs on the database thread. */
    public void recordPlacement(Block block) {
        if (!isTrackedMaterial(block.getType())) return;
        PlacedBlock placed = new PlacedBlock(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                block.getType().name(), System.currentTimeMillis());
        plugin.getDatabaseQueue().submit(() -> plugin.getDatabaseManager().recordPlacedBlock(placed));
    }

    /** Forgets a broken restricted block; the write runs on the database thread. */
    public void removePlacement(Block block) {
        if (!isTrackedMaterial(block.getType())) return;
        String world = block.getWorld().getName();
        int x = block.getX(), y = block.getY(), z = block.getZ();
        plugin.getDatabaseQueue().submit(() -> plugin.getDatabaseManager().removePlacedBlock(world, x, y, z));
    }

    /**
     * Loads every tracked coordinate in a world - called once at the start of a "player-placed only"
     * freeze scan. Blocks; runs on the database thread.
     */
    public Set<BlockKey> getPlacedKeysInWorld(String world) {
        Set<BlockKey> keys = new HashSet<>();
        for (PlacedBlock block : plugin.getDatabaseManager().getPlacedBlocksInWorld(world)) {
            keys.add(new BlockKey(block.x(), block.y(), block.z()));
        }
        return keys;
    }
}
