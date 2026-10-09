package com.dogetennant.dworldmanager.block;

import com.dogetennant.dworldmanager.DWorldManager;
import com.dogetennant.dworldmanager.util.Msg;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import java.util.List;

/**
 * Actually enforces block freezes: a block the cache says is frozen stays where it is. Players
 * cannot break it, pistons cannot move it (it would land on a spot that is not frozen), explosions
 * leave it standing, and mobs and fire cannot change it.
 */
public class BlockFreezeListener implements Listener {

    private final DWorldManager plugin;
    private final BlockFreezeService freezeService;

    public BlockFreezeListener(DWorldManager plugin, BlockFreezeService freezeService) {
        this.plugin = plugin;
        this.freezeService = freezeService;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (freezeService.isFrozen(block)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Msg.of(plugin, "freeze-block-break-denied",
                    "&cThis block has been grandfathered and can no longer be broken."));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (anyFrozen(event.getBlocks())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (anyFrozen(event.getBlocks())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(freezeService::isFrozen);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(freezeService::isFrozen);
    }

    /** Withers, endermen, falling blocks and the like. */
    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (freezeService.isFrozen(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        if (freezeService.isFrozen(event.getBlock())) event.setCancelled(true);
    }

    private boolean anyFrozen(List<Block> blocks) {
        for (Block block : blocks) {
            if (freezeService.isFrozen(block)) return true;
        }
        return false;
    }
}
