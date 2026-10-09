package com.dogetennant.dworldmanager.block;

import com.dogetennant.dworldmanager.DWorldManager;
import com.dogetennant.dworldmanager.Fakes;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A frozen (grandfathered) block must stay where it is: no player, piston, explosion or mob removes it. */
class BlockFreezeListenerTest {

    private final DWorldManager plugin = Fakes.plugin();
    private final World world = mock(World.class);
    private BlockFreezeListener listener;
    private Block frozen;
    private Block normal;

    @BeforeEach
    void setUp() {
        when(world.getName()).thenReturn("build");
        when(plugin.getDatabaseManager().getAllFrozenBlocks()).thenReturn(List.of(
                new FrozenBlock("build", 10, 64, 10, "DIAMOND_BLOCK", 0)));
        BlockFreezeService freezes = new BlockFreezeService(plugin, mock(PlacedBlockTracker.class));
        freezes.loadAllIntoCache();
        listener = new BlockFreezeListener(plugin, freezes);
        frozen = block(10, 64, 10, Material.DIAMOND_BLOCK);
        normal = block(11, 64, 10, Material.STONE);
    }

    private Block block(int x, int y, int z, Material type) {
        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(y);
        when(block.getZ()).thenReturn(z);
        when(block.getType()).thenReturn(type);
        return block;
    }

    @Test
    void aPlayerCannotBreakAFrozenBlock() {
        BlockBreakEvent breaking = mock(BlockBreakEvent.class);
        when(breaking.getBlock()).thenReturn(frozen);
        when(breaking.getPlayer()).thenReturn(mock(Player.class));

        Fakes.fire(listener, breaking);

        verify(breaking).setCancelled(true);
    }

    @Test
    void otherBlocksBreakNormally() {
        BlockBreakEvent breaking = mock(BlockBreakEvent.class);
        when(breaking.getBlock()).thenReturn(normal);

        Fakes.fire(listener, breaking);

        verify(breaking, never()).setCancelled(true);
    }

    @Test
    void aPistonCannotPushAFrozenBlock() {
        BlockPistonExtendEvent push = mock(BlockPistonExtendEvent.class);
        when(push.getBlocks()).thenReturn(List.of(normal, frozen));

        Fakes.fire(listener, push);

        verify(push).setCancelled(true);
    }

    @Test
    void aStickyPistonCannotPullAFrozenBlock() {
        BlockPistonRetractEvent pull = mock(BlockPistonRetractEvent.class);
        when(pull.getBlocks()).thenReturn(List.of(frozen));

        Fakes.fire(listener, pull);

        verify(pull).setCancelled(true);
    }

    @Test
    void aPistonMovingOtherBlocksWorks() {
        BlockPistonExtendEvent push = mock(BlockPistonExtendEvent.class);
        when(push.getBlocks()).thenReturn(List.of(normal));

        Fakes.fire(listener, push);

        verify(push, never()).setCancelled(true);
    }

    @Test
    void explosionsLeaveFrozenBlocksStanding() {
        List<Block> tnt = new ArrayList<>(List.of(normal, frozen));
        EntityExplodeEvent creeper = mock(EntityExplodeEvent.class);
        when(creeper.blockList()).thenReturn(tnt);
        List<Block> bed = new ArrayList<>(List.of(frozen, normal));
        BlockExplodeEvent respawnAnchor = mock(BlockExplodeEvent.class);
        when(respawnAnchor.blockList()).thenReturn(bed);

        Fakes.fire(listener, creeper);
        Fakes.fire(listener, respawnAnchor);

        assertThat(tnt).containsExactly(normal);
        assertThat(bed).containsExactly(normal);
    }

    @Test
    void mobsCannotTakeOrChangeAFrozenBlock() {
        EntityChangeBlockEvent wither = mock(EntityChangeBlockEvent.class);
        when(wither.getBlock()).thenReturn(frozen);

        Fakes.fire(listener, wither);

        verify(wither).setCancelled(true);
    }

    @Test
    void aFrozenBlockDoesNotBurn() {
        BlockBurnEvent fire = mock(BlockBurnEvent.class);
        when(fire.getBlock()).thenReturn(frozen);

        Fakes.fire(listener, fire);

        verify(fire).setCancelled(true);
    }
}
