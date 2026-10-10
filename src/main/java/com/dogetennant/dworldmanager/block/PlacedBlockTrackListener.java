package com.dogetennant.dworldmanager.block;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;

/** Records/clears player-placement tracking for restricted materials so freeze scans can tell placed blocks from natural ones. */
public class PlacedBlockTrackListener implements Listener {

    private final PlacedBlockTracker tracker;

    public PlacedBlockTrackListener(PlacedBlockTracker tracker) {
        this.tracker = tracker;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        tracker.recordPlacement(event.getBlockPlaced());
    }

    // MONITOR + ignoreCancelled so this only reacts to a break that actually
    // went through (e.g. wasn't denied by the frozen-block enforcement).
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        tracker.removePlacement(event.getBlock());
    }

    // Placed blocks a piston moves stay placed where they land. The server gives the direction the
    // blocks move in, for a pull too (towards the piston). A move with a frozen block is cancelled.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        tracker.movePlacements(event.getBlocks(), event.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        tracker.movePlacements(event.getBlocks(), event.getDirection());
    }
}
