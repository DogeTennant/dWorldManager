package com.dogetennant.dworldmanager.block;

import com.dogetennant.dworldmanager.DWorldManager;
import com.dogetennant.dworldmanager.Fakes;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Which placements are remembered for "player-placed only" freeze scans. */
class PlacedBlockTrackerTest {

    private final DWorldManager plugin = Fakes.plugin();
    private final World world = mock(World.class);
    private PlacedBlockTracker tracker;

    @BeforeEach
    void setUp() {
        when(world.getName()).thenReturn("build");
        when(plugin.getConfigManager().getRestrictedMaterials()).thenReturn(Set.of(Material.DIAMOND_BLOCK));
        tracker = new PlacedBlockTracker(plugin);
    }

    private Block block(Material type) {
        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(1);
        when(block.getY()).thenReturn(64);
        when(block.getZ()).thenReturn(-2);
        when(block.getType()).thenReturn(type);
        return block;
    }

    @Test
    void aPlacedRestrictedBlockIsRememberedAndForgottenWhenBroken() {
        tracker.recordPlacement(block(Material.DIAMOND_BLOCK));
        tracker.removePlacement(block(Material.DIAMOND_BLOCK));

        ArgumentCaptor<PlacedBlock> placed = ArgumentCaptor.forClass(PlacedBlock.class);
        verify(plugin.getDatabaseManager()).recordPlacedBlock(placed.capture());
        assertThat(placed.getValue()).extracting(PlacedBlock::world, PlacedBlock::x, PlacedBlock::y, PlacedBlock::z,
                PlacedBlock::material).containsExactly("build", 1, 64, -2, "DIAMOND_BLOCK");
        verify(plugin.getDatabaseManager()).removePlacedBlock("build", 1, 64, -2);
    }

    @Test
    void otherBlocksAreNotTracked() {
        tracker.recordPlacement(block(Material.STONE));
        tracker.removePlacement(block(Material.STONE));

        verify(plugin.getDatabaseManager(), never()).recordPlacedBlock(any());
        verify(plugin.getDatabaseManager(), never()).removePlacedBlock(anyString(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void aScanGetsThePlacedSpotsOfItsWorld() {
        when(plugin.getDatabaseManager().getPlacedBlocksInWorld("build")).thenReturn(List.of(
                new PlacedBlock("build", 1, 64, -2, "DIAMOND_BLOCK", 0)));

        assertThat(tracker.getPlacedKeysInWorld("build")).containsExactly(new BlockKey(1, 64, -2));
    }
}
