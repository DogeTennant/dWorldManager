package com.dogetennant.dworldmanager.block;

import com.dogetennant.dworldmanager.DWorldManager;
import com.dogetennant.dworldmanager.Fakes;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Freezing and unfreezing: which blocks, how many, and the cache the break check reads. */
class BlockFreezeServiceTest {

    private final DWorldManager plugin = Fakes.plugin();
    private final World world = mock(World.class);
    private final PlacedBlockTracker placed = mock(PlacedBlockTracker.class);
    /** What the database holds, as the mocked database sees it. */
    private final List<FrozenBlock> stored = new ArrayList<>();
    private BlockFreezeService freezes;

    @BeforeEach
    void setUp() {
        when(world.getName()).thenReturn("build");
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        var db = plugin.getDatabaseManager();
        when(db.getAllFrozenBlocks()).thenAnswer(call -> List.copyOf(stored));
        when(db.getFrozenBlocksInWorld("build")).thenAnswer(call -> List.copyOf(stored));
        doAnswer(call -> {
            // like the real table: freezing an already frozen spot keeps the first row
            for (FrozenBlock block : call.<Collection<FrozenBlock>>getArgument(0)) {
                if (stored.stream().noneMatch(s -> s.x() == block.x() && s.y() == block.y() && s.z() == block.z())) {
                    stored.add(block);
                }
            }
            return null;
        }).when(db).freezeBlocks(anyList());
        freezes = new BlockFreezeService(plugin, placed);
        freezes.loadAllIntoCache();
    }

    /** Every block in the world is stone except the given diamond blocks. */
    private void diamondBlocksAt(int[]... spots) {
        Block stone = mock(Block.class);
        when(stone.getType()).thenReturn(Material.STONE);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(stone);
        for (int[] spot : spots) {
            Block diamond = mock(Block.class);
            when(diamond.getType()).thenReturn(Material.DIAMOND_BLOCK);
            when(world.getBlockAt(spot[0], spot[1], spot[2])).thenReturn(diamond);
        }
    }

    private int freezeRegion(boolean placedOnly) {
        AtomicReference<Integer> frozen = new AtomicReference<>();
        freezes.freezeRegion(world, 0, 64, 0, 3, 65, 3, Set.of(Material.DIAMOND_BLOCK), placedOnly,
                new BlockFreezeService.ScanCallback() {
                    @Override public void onComplete(int count) { frozen.set(count); }
                    @Override public void onError(String message) { throw new AssertionError(message); }
                });
        return frozen.get();
    }

    @Test
    void aRegionScanFreezesTheMatchingBlocks() {
        diamondBlocksAt(new int[] {1, 64, 1}, new int[] {3, 65, 0});

        assertThat(freezeRegion(false)).isEqualTo(2);

        assertThat(freezes.isFrozen("build", 1, 64, 1)).isTrue();
        assertThat(freezes.isFrozen("build", 3, 65, 0)).isTrue();
        assertThat(freezes.isFrozen("build", 2, 64, 1)).isFalse();
    }

    @Test
    void scanningAgainCountsOnlyNewlyFrozenBlocks() {
        diamondBlocksAt(new int[] {1, 64, 1});
        freezeRegion(false);
        diamondBlocksAt(new int[] {1, 64, 1}, new int[] {2, 64, 2});

        assertThat(freezeRegion(false)).isEqualTo(1);
    }

    @Test
    void placedOnlySkipsNaturalBlocks() {
        diamondBlocksAt(new int[] {1, 64, 1}, new int[] {2, 64, 2});
        when(placed.getPlacedKeysInWorld("build")).thenReturn(Set.of(new BlockKey(2, 64, 2)));

        assertThat(freezeRegion(true)).isEqualTo(1);
        assertThat(freezes.isFrozen("build", 1, 64, 1)).isFalse();
    }

    @Test
    void aRegionLargerThanTheCapIsRefused() {
        when(plugin.getConfigManager().getMaxRegionVolume()).thenReturn(10L);
        AtomicReference<String> error = new AtomicReference<>();

        freezes.freezeRegion(world, 0, 64, 0, 3, 65, 3, Set.of(Material.DIAMOND_BLOCK), false,
                new BlockFreezeService.ScanCallback() {
                    @Override public void onComplete(int count) { throw new AssertionError("scanned"); }
                    @Override public void onError(String message) { error.set(message); }
                });

        assertThat(error.get()).contains("32");                  // 4 x 2 x 4 blocks
    }

    @Test
    void unfreezingOneBlockLetsItBeBrokenAndIsLogged() {
        stored.add(new FrozenBlock("build", 1, 64, 1, "DIAMOND_BLOCK", 0));
        freezes.loadAllIntoCache();
        when(plugin.getDatabaseManager().unfreezeBlock("build", 1, 64, 1)).thenReturn(true);
        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(1);
        when(block.getY()).thenReturn(64);
        when(block.getZ()).thenReturn(1);
        when(block.getType()).thenReturn(Material.DIAMOND_BLOCK);

        AtomicReference<Boolean> removed = new AtomicReference<>();
        freezes.unfreezeSingleBlock(block, UUID.randomUUID(), "Alex", removed::set);

        assertThat(removed.get()).isTrue();
        assertThat(freezes.isFrozen("build", 1, 64, 1)).isFalse();
        ArgumentCaptor<UnfreezeLogEntry> logged = ArgumentCaptor.forClass(UnfreezeLogEntry.class);
        verify(plugin.getDatabaseManager()).logUnfreeze(logged.capture());
        assertThat(logged.getValue().scope()).isEqualTo(UnfreezeLogEntry.Scope.BLOCK);
    }

    @Test
    void aRegionUnfreezeIsLoggedWithBothCornersAndRefreshesTheCache() {
        stored.add(new FrozenBlock("build", 5, 65, 5, "DIAMOND_BLOCK", 0));
        freezes.loadAllIntoCache();
        when(plugin.getDatabaseManager().unfreezeRegion("build", 10, 70, 10, 0, 60, 0)).thenAnswer(call -> {
            stored.clear();
            return 1;
        });
        AtomicReference<Integer> count = new AtomicReference<>();

        freezes.unfreezeRegion(world, 10, 70, 10, 0, 60, 0, UUID.randomUUID(), "Alex", count::set);

        assertThat(count.get()).isEqualTo(1);
        assertThat(freezes.isFrozen("build", 5, 65, 5)).isFalse();
        ArgumentCaptor<UnfreezeLogEntry> logged = ArgumentCaptor.forClass(UnfreezeLogEntry.class);
        verify(plugin.getDatabaseManager()).logUnfreeze(logged.capture());
        UnfreezeLogEntry entry = logged.getValue();
        assertThat(entry.scope()).isEqualTo(UnfreezeLogEntry.Scope.REGION);
        assertThat(new int[] {entry.x(), entry.y(), entry.z()}).containsExactly(0, 60, 0);
        assertThat(entry.regionEnd()).isEqualTo("10,70,10");
        assertThat(entry.affectedCount()).isEqualTo(1);
    }

    @Test
    void aWholeWorldUnfreezeIsLoggedAsSuchAndNothingIsLoggedWhenNothingWasFrozen() {
        when(plugin.getDatabaseManager().unfreezeWorld("build")).thenReturn(7, 0);
        AtomicReference<Integer> count = new AtomicReference<>();

        freezes.unfreezeAllInWorld(world, UUID.randomUUID(), "Alex", count::set);
        freezes.unfreezeAllInWorld(world, UUID.randomUUID(), "Alex", count::set);

        assertThat(count.get()).isZero();
        ArgumentCaptor<UnfreezeLogEntry> logged = ArgumentCaptor.forClass(UnfreezeLogEntry.class);
        verify(plugin.getDatabaseManager()).logUnfreeze(logged.capture());
        assertThat(logged.getValue().scope()).isEqualTo(UnfreezeLogEntry.Scope.WORLD);
        assertThat(logged.getValue().affectedCount()).isEqualTo(7);
    }

    @Test
    void anotherWorldsBlockIsNotFrozen() {
        stored.add(new FrozenBlock("build", 1, 64, 1, "DIAMOND_BLOCK", 0));
        freezes.loadAllIntoCache();

        assertThat(freezes.isFrozen("survival", 1, 64, 1)).isFalse();
        assertThat(freezes.isFrozen("build", 1, 64, 1)).isTrue();
    }
}
