package com.dogetennant.dworldmanager.container;

import com.dogetennant.dworldmanager.DWorldManager;
import com.dogetennant.dworldmanager.Fakes;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code /dwm clearcontainers}: what is cleared, and that the clearing is kept. */
class ContainerClearServiceTest {

    private final DWorldManager plugin = Fakes.plugin();
    private final ContainerTaintService taint = mock(ContainerTaintService.class);
    private final World world = mock(World.class);
    private final Chunk chunk = mock(Chunk.class);
    private ContainerClearService service;

    @BeforeEach
    void setUp() {
        WorldBorder border = mock(WorldBorder.class);
        when(border.getSize()).thenReturn(16.0);
        when(border.getCenter()).thenReturn(new Location(world, 8, 64, 8));
        when(world.getWorldBorder()).thenReturn(border);
        when(world.getName()).thenReturn("build");
        when(world.isChunkGenerated(0, 0)).thenReturn(true);
        when(world.getChunkAt(0, 0)).thenReturn(chunk);
        when(chunk.getTileEntities()).thenReturn(new BlockState[0]);
        when(chunk.getEntities()).thenReturn(new Entity[0]);
        service = new ContainerClearService(plugin, taint);
    }

    private Inventory chestIn(boolean tainted) {
        Chest chest = mock(Chest.class);
        Inventory inventory = mock(Inventory.class);
        when(inventory.getContents()).thenReturn(new ItemStack[0]);
        when(chest.getInventory()).thenReturn(inventory);
        when(chest.getType()).thenReturn(Material.CHEST);
        when(chest.getWorld()).thenReturn(world);
        when(taint.isTainted(chest)).thenReturn(tainted);
        when(chunk.getTileEntities()).thenReturn(new BlockState[] {chest});
        return inventory;
    }

    private ClearStats clear() {
        AtomicReference<ClearStats> done = new AtomicReference<>();
        service.clearTaintedInWorld(world, new ContainerClearService.ClearCallback() {
            @Override public void onComplete(ClearStats stats) { done.set(stats); }
            @Override public void onError(String message) { throw new AssertionError(message); }
        });
        return done.get();
    }

    @Test
    void aTaintedChestIsEmptiedAndAnUntaintedOneIsLeftAlone() {
        Inventory tainted = chestIn(true);
        assertThat(clear().containersCleared).isEqualTo(1);
        verify(tainted).clear();

        Inventory loot = chestIn(false);
        assertThat(clear().containersCleared).isZero();
        verify(loot, never()).clear();
    }

    @Test
    void aChunkLoadedOnlyForTheClearIsSavedWhenItIsUnloaded() {
        when(world.isChunkLoaded(0, 0)).thenReturn(false);
        chestIn(true);

        clear();

        // unloading without saving would throw the cleared chest away: it is full again next time
        verify(world).unloadChunk(0, 0, true);
        verify(world, never()).unloadChunk(0, 0, false);
    }

    @Test
    void aChunkThatWasAlreadyLoadedStaysLoaded() {
        when(world.isChunkLoaded(0, 0)).thenReturn(true);
        chestIn(true);

        clear();

        verify(world, never()).unloadChunk(anyInt(), anyInt(), anyBoolean());
    }

    /** Item frames and armor stands need a real server (Material#isAir reads its registries): MockBukkit, later. */
    @Test
    void droppedItemsAreRemoved() {
        Item dropped = mock(Item.class);
        when(chunk.getEntities()).thenReturn(new Entity[] {dropped});

        ClearStats stats = clear();

        verify(dropped).remove();
        assertThat(stats.droppedItemsRemoved).isEqualTo(1);
    }

    @Test
    void aWorldWithoutARealBorderIsRefused() {
        when(world.getWorldBorder().getSize()).thenReturn(5.9e7);
        AtomicReference<String> error = new AtomicReference<>();

        service.clearTaintedInWorld(world, new ContainerClearService.ClearCallback() {
            @Override public void onComplete(ClearStats stats) { throw new AssertionError("cleared"); }
            @Override public void onError(String message) { error.set(message); }
        });

        assertThat(error.get()).contains("build");
    }
}
