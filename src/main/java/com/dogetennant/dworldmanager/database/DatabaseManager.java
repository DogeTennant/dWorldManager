package com.dogetennant.dworldmanager.database;

import com.dogetennant.dworldmanager.DWorldManager;
import com.dogetennant.dworldmanager.block.FrozenBlock;
import com.dogetennant.dworldmanager.block.PlacedBlock;
import com.dogetennant.dworldmanager.block.UnfreezeLogEntry;
import com.zaxxer.hikari.HikariDataSource;

import java.util.List;

public abstract class DatabaseManager {

    protected final DWorldManager plugin;
    protected HikariDataSource dataSource;

    // Table names - built once from the configured prefix
    protected final String frozenBlocksTable;
    protected final String unfreezeLogTable;
    protected final String placedBlocksTable;

    public DatabaseManager(DWorldManager plugin) {
        this.plugin = plugin;
        String prefix = plugin.getConfigManager().getTablePrefix();
        this.frozenBlocksTable = prefix + "frozen_blocks";
        this.unfreezeLogTable  = prefix + "unfreeze_log";
        this.placedBlocksTable = prefix + "placed_blocks";
    }

    //
    // Lifecycle - implemented by MySQLManager / SQLiteManager
    //

    /** Set up the HikariCP pool and create tables. Throws on failure. */
    public abstract void initialize() throws Exception;

    /** Close the connection pool cleanly on server shutdown. */
    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.getLogger().info("Database connection pool closed.");
        }
    }

    /** Called by both MySQL and SQLite after the pool is ready. */
    protected abstract void createTables() throws Exception;

    //
    // Frozen blocks (permanent grandfathering of restricted materials)
    //

    /** Grandfathers a block at the given coordinates. No-op if already frozen. */
    public abstract void freezeBlock(FrozenBlock block);

    /** Grandfathers many blocks in one transaction. Used by full-world freeze scans. */
    public abstract void freezeBlocks(List<FrozenBlock> blocks);

    /** Returns the frozen-block record at these coordinates, or null if not frozen. */
    public abstract FrozenBlock getFrozenBlock(String world, int x, int y, int z);

    /** Removes the freeze at these coordinates. Returns true if a row was actually removed. */
    public abstract boolean unfreezeBlock(String world, int x, int y, int z);

    /** Removes every freeze within the inclusive coordinate box. Returns the number removed. */
    public abstract int unfreezeRegion(String world, int x1, int y1, int z1, int x2, int y2, int z2);

    /** Removes every freeze in an entire world, regardless of material. Returns the number removed. */
    public abstract int unfreezeWorld(String world);

    /** Removes every freeze of one material in a world. Returns the number removed. */
    public abstract int unfreezeMaterial(String world, String material);

    /** Loads every frozen block (called once at startup to populate an in-memory cache). */
    public abstract List<FrozenBlock> getAllFrozenBlocks();

    /** Loads every frozen block in one world (used to refresh the in-memory cache after a freeze/unfreeze). */
    public abstract List<FrozenBlock> getFrozenBlocksInWorld(String world);

    //
    // Placed blocks (tracks player placement of restricted materials that have
    // no tile entity, so can't carry a PersistentDataContainer, e.g. ores)
    //

    /** Records that a player placed a trackable-material block here. Upserts (refreshes material/time on conflict). */
    public abstract void recordPlacedBlock(PlacedBlock block);

    /** Removes the placement record at these coordinates (called when the block is broken). Returns true if a row was removed. */
    public abstract boolean removePlacedBlock(String world, int x, int y, int z);

    /** Loads every tracked placement in one world (used when a "player-placed only" freeze scan starts). */
    public abstract List<PlacedBlock> getPlacedBlocksInWorld(String world);

    /** Loads every tracked placement across all worlds (used only by SQLite -> MySQL migration). */
    public abstract List<PlacedBlock> getAllPlacedBlocks();

    //
    // Unfreeze audit log
    //

    /** Records a staff unfreeze action for later review. */
    public abstract void logUnfreeze(UnfreezeLogEntry entry);

    /** Returns the most recent unfreeze log entries, newest first. */
    public abstract List<UnfreezeLogEntry> getUnfreezeLog(int limit);

    //
    // Shared utility
    //

    /** Returns true if the pool is open and healthy. */
    public boolean isConnected() {
        return dataSource != null && !dataSource.isClosed();
    }
}
