package com.dogetennant.dworldmanager.database;

import com.dogetennant.dworldmanager.DWorldManager;
import com.dogetennant.dworldmanager.block.FrozenBlock;
import com.dogetennant.dworldmanager.block.PlacedBlock;
import com.dogetennant.dworldmanager.block.UnfreezeLogEntry;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.util.List;
import java.util.logging.Level;

/**
 * Migrates all data from SQLite to MySQL.
 *
 * How it works:
 *   1. Temporarily opens a SQLiteManager pointed at data.db
 *   2. Reads all frozen blocks and unfreeze log entries
 *   3. Writes them into the active MySQL database
 *   4. Reports results to the command sender
 *
 * The SQLite file is never deleted - the server owner must manually
 * switch storage.type to mysql in config.yml and restart.
 *
 * Migration runs fully async so the server doesn't freeze.
 */
public class MigrationManager {

    private final DWorldManager plugin;

    public MigrationManager(DWorldManager plugin) {
        this.plugin = plugin;
    }

    public void migrate(CommandSender sender) {
        // Must be running MySQL as the active backend
        if (!(plugin.getDatabaseManager() instanceof MySQLManager)) {
            sender.sendMessage("Migration aborted: storage.type must be 'mysql' in config.yml to migrate into.");
            plugin.getLogger().warning(
                    "Migration aborted: storage.type must be 'mysql' to migrate into.");
            return;
        }

        File sqliteFile = new File(plugin.getDataFolder(), "data.db");
        if (!sqliteFile.exists()) {
            sender.sendMessage("No SQLite data.db file found - nothing to migrate.");
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // Open a temporary SQLite connection for reading
            SQLiteManager source = new SQLiteManager(plugin);
            try {
                source.initialize();
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE,
                        "Migration failed: could not open SQLite file.", e);
                Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage("Migration failed - check the console for details."));
                return;
            }

            try {
                MySQLManager target = (MySQLManager) plugin.getDatabaseManager();

                List<FrozenBlock> frozenBlocks = source.getAllFrozenBlocks();
                target.freezeBlocks(frozenBlocks);

                List<UnfreezeLogEntry> logEntries = source.getUnfreezeLog(Integer.MAX_VALUE);
                for (UnfreezeLogEntry entry : logEntries) {
                    target.logUnfreeze(entry);
                }

                List<PlacedBlock> placedBlocks = source.getAllPlacedBlocks();
                for (PlacedBlock block : placedBlocks) {
                    target.recordPlacedBlock(block);
                }

                final int finalFrozen = frozenBlocks.size();
                final int finalLogs   = logEntries.size();
                final int finalPlaced = placedBlocks.size();

                Bukkit.getScheduler().runTask(plugin, () -> {
                    sender.sendMessage("Migration complete: " + finalFrozen + " frozen block(s), "
                            + finalLogs + " unfreeze log entr(y/ies), " + finalPlaced + " placed block record(s) migrated.");
                    plugin.getLogger().info("Migration complete: "
                            + finalFrozen + " frozen blocks, " + finalLogs
                            + " unfreeze log entries, " + finalPlaced + " placed block records migrated.");
                    plugin.getLogger().info(
                            "Change storage.type to 'mysql' in config.yml "
                                    + "and restart the server to use MySQL.");
                });

            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Migration failed.", e);
                Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage("Migration failed - check the console for details."));
            } finally {
                source.shutdown();
            }
        });
    }
}
