package com.dogetennant.dworldmanager.block;

import com.dogetennant.dworldmanager.DWorldManager;
import com.dogetennant.dworldmanager.util.Msg;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

/** Actually enforces block freezes - denies breaking any block the cache says is frozen. */
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
}
