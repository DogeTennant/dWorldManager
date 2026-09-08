package com.dogetennant.dworldmanager.util;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared logic for scanning every generated chunk in a world without lagging
 * the server. The chunk list is bounded by the world's border (with a safety
 * cap, since an unset border is effectively infinite). Chunks are then walked
 * a bit at a time per tick, bounded by a wall-clock time budget rather than a
 * fixed chunk count - checking whether a chunk is even generated can itself
 * block briefly on the chunk system, so a fixed count can't guarantee a safe
 * per-tick cost the way a time budget can. Any chunk that wasn't already
 * loaded is unloaded again right after it's visited.
 */
public final class ChunkWalker {

    private ChunkWalker() {
    }

    /**
     * Returns every chunk coordinate inside the world's border (generation status
     * is NOT checked here - that happens incrementally during walk() so a huge
     * border can't block the calling thread), or null if the border is bigger
     * than maxBorderSize (caller should treat that as "refuse to scan, ask the
     * admin to set a real border").
     */
    public static List<int[]> planChunks(World world, double maxBorderSize) {
        WorldBorder border = world.getWorldBorder();
        double size = border.getSize();
        if (size > maxBorderSize) return null;

        Location center = border.getCenter();
        int centerChunkX = center.getBlockX() >> 4;
        int centerChunkZ = center.getBlockZ() >> 4;
        int radius = (int) Math.ceil(size / 2.0 / 16.0);

        List<int[]> coords = new ArrayList<>();
        for (int cx = centerChunkX - radius; cx <= centerChunkX + radius; cx++) {
            for (int cz = centerChunkZ - radius; cz <= centerChunkZ + radius; cz++) {
                coords.add(new int[]{cx, cz});
            }
        }
        return coords;
    }

    public interface ChunkVisitor {
        void visit(Chunk chunk);
    }

    public interface ProgressListener {
        void onProgress(int scanned, int total);
    }

    /**
     * Walks the given chunk coordinates, spending at most tickBudgetMillis of
     * wall-clock time per tick. For each candidate coordinate, checks whether
     * it's actually generated (skipping it if not - this never force-generates
     * new terrain), then calls visitor.visit(chunk). progressListener (nullable)
     * is pinged roughly every 5 seconds with how far through the list we are.
     * Calls onDone once every chunk has been considered.
     */
    public static void walk(Plugin plugin, World world, List<int[]> chunkCoords, long tickBudgetMillis,
                             ChunkVisitor visitor, ProgressListener progressListener, Runnable onDone) {
        int[] index = {0};
        long[] lastProgressAt = {System.nanoTime()};

        plugin.getServer().getScheduler().runTaskTimer(plugin, task -> {
            long deadline = System.nanoTime() + (tickBudgetMillis * 1_000_000L);
            while (index[0] < chunkCoords.size() && System.nanoTime() < deadline) {
                int[] coord = chunkCoords.get(index[0]++);
                if (world.isChunkGenerated(coord[0], coord[1])) {
                    boolean wasLoaded = world.isChunkLoaded(coord[0], coord[1]);
                    Chunk chunk = world.getChunkAt(coord[0], coord[1]);
                    visitor.visit(chunk);
                    if (!wasLoaded) {
                        world.unloadChunk(coord[0], coord[1], false);
                    }
                }
            }

            if (progressListener != null && System.nanoTime() - lastProgressAt[0] > 5_000_000_000L) {
                lastProgressAt[0] = System.nanoTime();
                progressListener.onProgress(index[0], chunkCoords.size());
            }

            if (index[0] >= chunkCoords.size()) {
                task.cancel();
                onDone.run();
            }
        }, 0L, 1L);
    }
}
