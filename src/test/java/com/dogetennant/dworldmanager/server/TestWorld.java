package com.dogetennant.dworldmanager.server;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.mockbukkit.mockbukkit.world.ChunkMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A MockBukkit world with the chunk calls the world scans need and MockBukkit leaves out or gets
 * wrong: whether a chunk is generated (every chunk is, except the ones passed to
 * {@link #notGenerated}), a chunk's block entities (every block in it whose state is a
 * {@link TileState}, as on a server) and a chunk snapshot. Bedrock at y 0, dirt up to y 3, air up
 * to y 31.
 */
final class TestWorld extends WorldMock {

    private final Set<Long> notGenerated = new HashSet<>();
    private final Set<Long> loadedSoFar = new HashSet<>();

    TestWorld(String name, double borderSize) {
        super(Material.DIRT, 0, 32, 3);
        setName(name);
        getWorldBorder().setSize(borderSize);
    }

    /** Nobody has been to this chunk yet: the server has never generated it. */
    void notGenerated(int chunkX, int chunkZ) {
        notGenerated.add(key(chunkX, chunkZ));
    }

    @Override
    public boolean isChunkGenerated(int x, int z) {
        return !notGenerated.contains(key(x, z));
    }

    /** Whether anything has asked for this chunk (and so loaded it) since the world was made. */
    boolean wasEverLoaded(int chunkX, int chunkZ) {
        return loadedSoFar.contains(key(chunkX, chunkZ));
    }

    @Override
    public ChunkMock getChunkAt(int x, int z) {
        super.getChunkAt(x, z); // loads it, as on a server
        loadedSoFar.add(key(x, z));
        return new Chunk(this, x, z);
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    private static final class Chunk extends ChunkMock {

        Chunk(World world, int x, int z) {
            super(world, x, z);
        }

        /**
         * The chunk's block types, copied now. MockBukkit's own snapshot loses every block of a
         * chunk at negative coordinates (it keys them by {@code x % 16}, which is negative there).
         */
        @Override
        public ChunkSnapshot getChunkSnapshot() {
            int minHeight = getWorld().getMinHeight();
            Material[][][] types = new Material[16][getWorld().getMaxHeight() - minHeight][16];
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < types[x].length; y++) {
                    for (int z = 0; z < 16; z++) {
                        types[x][y][z] = getBlock(x, minHeight + y, z).getType();
                    }
                }
            }
            ChunkSnapshot snapshot = mock(ChunkSnapshot.class);
            when(snapshot.getBlockType(anyInt(), anyInt(), anyInt())).thenAnswer(call ->
                    types[call.<Integer>getArgument(0)][call.<Integer>getArgument(1) - minHeight][call.<Integer>getArgument(2)]);
            return snapshot;
        }

        @Override
        public BlockState[] getTileEntities() {
            return getTileEntities(true);
        }

        @Override
        public BlockState[] getTileEntities(boolean useSnapshot) {
            List<BlockState> states = new ArrayList<>();
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = getWorld().getMinHeight(); y < getWorld().getMaxHeight(); y++) {
                        BlockState state = getBlock(x, y, z).getState();
                        if (state instanceof TileState) states.add(state);
                    }
                }
            }
            return states.toArray(BlockState[]::new);
        }
    }
}
