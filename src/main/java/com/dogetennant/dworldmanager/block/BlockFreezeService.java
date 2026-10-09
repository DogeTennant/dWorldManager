package com.dogetennant.dworldmanager.block;

import com.dogetennant.dworldmanager.DWorldManager;
import com.dogetennant.dworldmanager.database.DatabaseQueue;
import com.dogetennant.dworldmanager.util.ChunkWalker;
import com.dogetennant.dworldmanager.util.Msg;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * Scans a world for restricted materials and grandfathers ("freezes") every
 * match, and handles bulk unfreezing by world or by material.
 *
 * Frozen coordinates are also mirrored into an in-memory cache so the
 * BlockBreakEvent listener never has to hit the database on every single
 * block break in the world - only ones that are actually frozen matter, and
 * even then it's a plain in-memory set lookup. The cache is only ever
 * touched from the main thread (loaded at startup, refreshed after a freeze
 * scan finishes or an unfreeze runs), so no synchronization is needed.
 *
 * Database work runs on the {@link DatabaseQueue}, in order (an unfreeze never overtakes the last
 * batch of a freeze scan); results come back on the main thread.
 *
 * The freeze scan is spread over multiple ticks (bounded by a configurable
 * per-tick time budget) instead of running in one go, so a large build world
 * doesn't cause a noticeable lag spike. Chunks that weren't already loaded
 * are unloaded again after their snapshot is taken.
 */
public class BlockFreezeService {

    /** Sentinel UUID used for unfreeze actions run from the console (no player UUID exists). */
    public static final UUID CONSOLE_UUID = new UUID(0, 0);

    private final DWorldManager plugin;
    private final PlacedBlockTracker placedBlockTracker;
    private final Map<String, Set<BlockKey>> frozenCache = new HashMap<>();

    public BlockFreezeService(DWorldManager plugin, PlacedBlockTracker placedBlockTracker) {
        this.plugin = plugin;
        this.placedBlockTracker = placedBlockTracker;
    }

    public interface ScanCallback {
        void onComplete(int frozen);
        void onError(String message);
        default void onProgress(int scanned, int total) {
        }
    }

    private DatabaseQueue queue() {
        return plugin.getDatabaseQueue();
    }

    //
    // In-memory cache
    //

    /** Loads every frozen block from the database into the cache. Call once at startup. */
    public void loadAllIntoCache() {
        frozenCache.clear();
        for (FrozenBlock block : plugin.getDatabaseManager().getAllFrozenBlocks()) {
            frozenCache.computeIfAbsent(block.world(), w -> new HashSet<>())
                    .add(new BlockKey(block.x(), block.y(), block.z()));
        }
    }

    /** One world's frozen blocks as stored. Database thread. */
    private Set<BlockKey> storedKeys(String world) {
        Set<BlockKey> keys = new HashSet<>();
        for (FrozenBlock block : plugin.getDatabaseManager().getFrozenBlocksInWorld(world)) {
            keys.add(new BlockKey(block.x(), block.y(), block.z()));
        }
        return keys;
    }

    /** Whether the block at these coordinates is currently frozen. Checked on every block break. */
    public boolean isFrozen(String world, int x, int y, int z) {
        Set<BlockKey> keys = frozenCache.get(world);
        return keys != null && keys.contains(new BlockKey(x, y, z));
    }

    public boolean isFrozen(Block block) {
        return isFrozen(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    //
    // Freeze (single block)
    //

    /** Grandfathers exactly one block, e.g. the one a staff member is looking at. No-op if already frozen. */
    public void freezeSingleBlock(Block block) {
        if (isFrozen(block)) return;
        FrozenBlock frozen = new FrozenBlock(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                block.getType().name(), System.currentTimeMillis());
        frozenCache.computeIfAbsent(block.getWorld().getName(), w -> new HashSet<>())
                .add(new BlockKey(block.getX(), block.getY(), block.getZ()));
        queue().submit(() -> plugin.getDatabaseManager().freezeBlock(frozen));
    }

    /** Unfreezes exactly one block; {@code then} gets false if it wasn't frozen. */
    public void unfreezeSingleBlock(Block block, UUID staffUuid, String staffName, Consumer<Boolean> then) {
        String world = block.getWorld().getName();
        int x = block.getX(), y = block.getY(), z = block.getZ();
        UnfreezeLogEntry entry = new UnfreezeLogEntry(-1, world, x, y, z, block.getType().name(),
                1, staffUuid, staffName, System.currentTimeMillis(), UnfreezeLogEntry.Scope.BLOCK, null);
        queue().query(() -> {
            boolean removed = plugin.getDatabaseManager().unfreezeBlock(world, x, y, z);
            if (removed) plugin.getDatabaseManager().logUnfreeze(entry);
            return removed;
        }, removed -> {
            if (removed) {
                Set<BlockKey> keys = frozenCache.get(world);
                if (keys != null) keys.remove(new BlockKey(x, y, z));
            }
            then.accept(removed);
        });
    }

    //
    // Freeze (bounded region)
    //

    /** Scans an explicit coordinate box and freezes every matching block. Bounded by blocks.max-region-volume. */
    public void freezeRegion(World world, int x1, int y1, int z1, int x2, int y2, int z2,
                              Set<Material> materials, boolean playerPlacedOnly, ScanCallback callback) {
        if (materials.isEmpty()) {
            callback.onError(Msg.text(plugin, "freeze-no-materials",
                    "&cNo materials given and none configured in blocks.restricted-materials - nothing to freeze."));
            return;
        }

        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.max(world.getMinHeight(), Math.min(y1, y2));
        int maxY = Math.min(world.getMaxHeight() - 1, Math.max(y1, y2));
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);

        int sizeX = maxX - minX + 1;
        int sizeY = maxY - minY + 1;
        int sizeZ = maxZ - minZ + 1;
        long volume = (long) sizeX * sizeY * sizeZ;

        long maxVolume = plugin.getConfigManager().getMaxRegionVolume();
        if (volume > maxVolume) {
            callback.onError(Msg.text(plugin, "region-too-big",
                    "&cThat region is %volume% blocks - larger than the configured safety cap "
                            + "(blocks.max-region-volume: %max%). Select a smaller region.",
                    "volume", String.valueOf(volume), "max", String.valueOf(maxVolume)));
            return;
        }

        withPlacedKeys(world, playerPlacedOnly, placedKeys -> {
            long tickBudgetMillis = plugin.getConfigManager().getScanTickBudgetMillis();
            List<FrozenBlock> pendingBatch = new ArrayList<>();
            int[] totalFrozen = {0};
            long[] index = {0};

            plugin.getServer().getScheduler().runTaskTimer(plugin, task -> {
                long deadline = System.nanoTime() + (tickBudgetMillis * 1_000_000L);
                while (index[0] < volume && System.nanoTime() < deadline) {
                    long i = index[0]++;
                    int x = minX + (int) (i / ((long) sizeY * sizeZ));
                    long rem = i % ((long) sizeY * sizeZ);
                    int y = minY + (int) (rem / sizeZ);
                    int z = minZ + (int) (rem % sizeZ);

                    if (isFrozen(world.getName(), x, y, z)) continue;    // counted only once, when it was frozen
                    Material type = world.getBlockAt(x, y, z).getType();
                    if (materials.contains(type) && (placedKeys == null || placedKeys.contains(new BlockKey(x, y, z)))) {
                        pendingBatch.add(new FrozenBlock(world.getName(), x, y, z, type.name(), System.currentTimeMillis()));
                        if (pendingBatch.size() >= 2000) {
                            flushBatch(pendingBatch, totalFrozen);
                        }
                    }
                }

                if (index[0] >= volume) {
                    task.cancel();
                    flushBatch(pendingBatch, totalFrozen);
                    finishScan(world, totalFrozen, callback);
                }
            }, 0L, 1L);
        });
    }

    /** Unfreezes every block in an explicit coordinate box; {@code then} gets the number unfrozen. */
    public void unfreezeRegion(World world, int x1, int y1, int z1, int x2, int y2, int z2,
                               UUID staffUuid, String staffName, IntConsumer then) {
        String name = world.getName();
        UnfreezeLogEntry entry = new UnfreezeLogEntry(-1, name, Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                "*", 0, staffUuid, staffName, System.currentTimeMillis(), UnfreezeLogEntry.Scope.REGION,
                Math.max(x1, x2) + "," + Math.max(y1, y2) + "," + Math.max(z1, z2));
        bulkUnfreeze(name, () -> plugin.getDatabaseManager().unfreezeRegion(name, x1, y1, z1, x2, y2, z2), entry, then);
    }

    //
    // Freeze (world-wide scan)
    //

    /** Scans the whole world (bounded by its world border) and freezes every matching block. */
    public void freezeAllInWorld(World world, Set<Material> materials, boolean playerPlacedOnly, ScanCallback callback) {
        if (materials.isEmpty()) {
            callback.onError(Msg.text(plugin, "freeze-no-materials",
                    "&cNo materials given and none configured in blocks.restricted-materials - nothing to freeze."));
            return;
        }

        double maxBorder = plugin.getConfigManager().getMaxScanBorderSize();
        List<int[]> chunkCoords = ChunkWalker.planChunks(world, maxBorder);
        if (chunkCoords == null) {
            callback.onError(Msg.text(plugin, "border-too-big",
                    "&cWorld border for '%world%' is larger than the configured safety cap "
                            + "(blocks.max-scan-border-size: %max%). Set a real border on this world first.",
                    "world", world.getName(), "max", String.valueOf((long) maxBorder)));
            return;
        }

        withPlacedKeys(world, playerPlacedOnly, placedKeys -> {
            long tickBudgetMillis = plugin.getConfigManager().getScanTickBudgetMillis();
            List<FrozenBlock> pendingBatch = new ArrayList<>();
            int[] totalFrozen = {0};

            ChunkWalker.walk(plugin, world, chunkCoords, tickBudgetMillis, false,
                    chunk -> {
                        scanChunk(chunk, materials, placedKeys, pendingBatch);
                        if (pendingBatch.size() >= 2000) {
                            flushBatch(pendingBatch, totalFrozen);
                        }
                    },
                    callback::onProgress,
                    () -> {
                        flushBatch(pendingBatch, totalFrozen);
                        finishScan(world, totalFrozen, callback);
                    });
        });
    }

    /** Hands {@code scan} the player-placed spots of the world when the scan is "placed only", else null. */
    private void withPlacedKeys(World world, boolean playerPlacedOnly, Consumer<Set<BlockKey>> scan) {
        if (!playerPlacedOnly) {
            scan.accept(null);
            return;
        }
        queue().query(() -> placedBlockTracker.getPlacedKeysInWorld(world.getName()), scan);
    }

    private void scanChunk(Chunk chunk, Set<Material> materials, Set<BlockKey> placedKeys, List<FrozenBlock> out) {
        World world = chunk.getWorld();
        ChunkSnapshot snapshot = chunk.getChunkSnapshot();

        int minHeight = world.getMinHeight();
        int maxHeight = world.getMaxHeight();
        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;
        long now = System.currentTimeMillis();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minHeight; y < maxHeight; y++) {
                    Material type = snapshot.getBlockType(x, y, z);
                    if (!materials.contains(type)) continue;
                    int worldX = baseX + x;
                    int worldZ = baseZ + z;
                    if (isFrozen(world.getName(), worldX, y, worldZ)) continue;  // counted only once
                    if (placedKeys == null || placedKeys.contains(new BlockKey(worldX, y, worldZ))) {
                        out.add(new FrozenBlock(world.getName(), worldX, y, worldZ, type.name(), now));
                    }
                }
            }
        }
    }

    /** Queues a batch of newly-found blocks for the database. */
    private void flushBatch(List<FrozenBlock> batch, int[] totalFrozen) {
        if (batch.isEmpty()) return;
        List<FrozenBlock> copy = new ArrayList<>(batch);
        totalFrozen[0] += copy.size();
        batch.clear();
        queue().submit(() -> plugin.getDatabaseManager().freezeBlocks(copy));
    }

    /**
     * Re-reads the world's frozen blocks once every batch is written (the queue runs in order) and
     * reports the scan as done.
     */
    private void finishScan(World world, int[] totalFrozen, ScanCallback callback) {
        queue().query(() -> storedKeys(world.getName()), keys -> {
            frozenCache.put(world.getName(), keys);
            callback.onComplete(totalFrozen[0]);
        });
    }

    //
    // Unfreeze (bulk, by world / by material)
    //

    /** Unfreezes every block in a world, regardless of material; {@code then} gets the number unfrozen. */
    public void unfreezeAllInWorld(World world, UUID staffUuid, String staffName, IntConsumer then) {
        String name = world.getName();
        UnfreezeLogEntry entry = new UnfreezeLogEntry(-1, name, 0, 0, 0, "*", 0, staffUuid, staffName,
                System.currentTimeMillis(), UnfreezeLogEntry.Scope.WORLD, null);
        bulkUnfreeze(name, () -> plugin.getDatabaseManager().unfreezeWorld(name), entry, then);
    }

    /** Unfreezes every block of one material in a world; {@code then} gets the number unfrozen. */
    public void unfreezeMaterialInWorld(World world, Material material, UUID staffUuid, String staffName,
                                        IntConsumer then) {
        String name = world.getName();
        UnfreezeLogEntry entry = new UnfreezeLogEntry(-1, name, 0, 0, 0, material.name(), 0, staffUuid, staffName,
                System.currentTimeMillis(), UnfreezeLogEntry.Scope.WORLD, null);
        bulkUnfreeze(name, () -> plugin.getDatabaseManager().unfreezeMaterial(name, material.name()), entry, then);
    }

    private record Unfrozen(int count, Set<BlockKey> remaining) {}

    /**
     * Runs a bulk unfreeze on the database thread, logs it with its count when it unfroze anything,
     * then refreshes the world's cache on the main thread and gives {@code then} the count.
     */
    private void bulkUnfreeze(String world, IntSupplier unfreeze, UnfreezeLogEntry entry,
                              IntConsumer then) {
        queue().query(() -> {
            int count = unfreeze.getAsInt();
            if (count > 0) {
                plugin.getDatabaseManager().logUnfreeze(new UnfreezeLogEntry(entry.id(), entry.world(),
                        entry.x(), entry.y(), entry.z(), entry.material(), count, entry.staffUuid(),
                        entry.staffName(), entry.unfrozenAt(), entry.scope(), entry.regionEnd()));
            }
            return new Unfrozen(count, storedKeys(world));
        }, result -> {
            frozenCache.put(world, result.remaining());
            then.accept(result.count());
        });
    }
}
