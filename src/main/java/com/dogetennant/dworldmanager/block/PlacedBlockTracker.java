package com.dogetennant.dworldmanager.block;

import com.dogetennant.dworldmanager.DWorldManager;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

    private record Move(int x, int y, int z, PlacedBlock landing) {}

    /**
     * Moves the records of placed blocks a piston moves one block {@code towards}: every old spot
     * is forgotten first, then the spots they land on are recorded (a row of blocks moves into its
     * own old spots). Natural blocks it moves stay natural. The writes run on the database thread.
     */
    public void movePlacements(List<Block> moved, BlockFace towards) {
        List<Move> moves = new ArrayList<>();
        String world = null;
        long now = System.currentTimeMillis();
        for (Block block : moved) {
            if (!isTrackedMaterial(block.getType())) continue;
            world = block.getWorld().getName();
            Block landing = block.getRelative(towards);
            moves.add(new Move(block.getX(), block.getY(), block.getZ(), new PlacedBlock(world,
                    landing.getX(), landing.getY(), landing.getZ(), block.getType().name(), now)));
        }
        if (moves.isEmpty()) return;

        String movedIn = world;
        plugin.getDatabaseQueue().submit(() -> {
            List<PlacedBlock> placed = new ArrayList<>();
            for (Move move : moves) {
                if (plugin.getDatabaseManager().removePlacedBlock(movedIn, move.x(), move.y(), move.z())) {
                    placed.add(move.landing());
                }
            }
            for (PlacedBlock landing : placed) {
                plugin.getDatabaseManager().recordPlacedBlock(landing);
            }
        });
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
