package com.dogetennant.dworldmanager;

import com.dogetennant.dworldmanager.block.BlockFreezeListener;
import com.dogetennant.dworldmanager.block.BlockFreezeService;
import com.dogetennant.dworldmanager.block.PlacedBlockTrackListener;
import com.dogetennant.dworldmanager.block.PlacedBlockTracker;
import com.dogetennant.dworldmanager.command.DWorldManagerCommand;
import com.dogetennant.dworldmanager.config.ConfigManager;
import com.dogetennant.dworldmanager.container.ContainerClearService;
import com.dogetennant.dworldmanager.container.ContainerTaintListener;
import com.dogetennant.dworldmanager.container.ContainerTaintService;
import com.dogetennant.dworldmanager.database.DatabaseManager;
import com.dogetennant.dworldmanager.database.MigrationManager;
import com.dogetennant.dworldmanager.database.MySQLManager;
import com.dogetennant.dworldmanager.database.SQLiteManager;
import org.bukkit.plugin.java.JavaPlugin;

public class DWorldManager extends JavaPlugin {

    //  Singleton
    private static DWorldManager instance;

    public static DWorldManager getInstance() {
        return instance;
    }

    //  Managers
    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private MigrationManager migrationManager;
    private BlockFreezeService blockFreezeService;
    private PlacedBlockTracker placedBlockTracker;
    private ContainerTaintService containerTaintService;
    private ContainerClearService containerClearService;

    //
    // Lifecycle
    //

    @Override
    public void onEnable() {
        instance = this;

        // 1. Config
        configManager = new ConfigManager(this);
        configManager.load();

        // 2. Database
        if (!setupDatabase()) {
            getLogger().severe("Failed to connect to the database. Disabling dWorldManager.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        migrationManager = new MigrationManager(this);
        placedBlockTracker = new PlacedBlockTracker(this);
        blockFreezeService = new BlockFreezeService(this, placedBlockTracker);
        blockFreezeService.loadAllIntoCache();
        containerTaintService = new ContainerTaintService(this);
        containerClearService = new ContainerClearService(this, containerTaintService);

        // 3. Listeners
        getServer().getPluginManager().registerEvents(new ContainerTaintListener(this, containerTaintService), this);
        getServer().getPluginManager().registerEvents(new BlockFreezeListener(this, blockFreezeService), this);
        getServer().getPluginManager().registerEvents(new PlacedBlockTrackListener(placedBlockTracker), this);

        // 4. Commands
        registerCommands();

        getLogger().info("dWorldManager enabled successfully.");
    }

    @Override
    public void onDisable() {
        if (databaseManager != null) {
            databaseManager.shutdown();
        }
        getLogger().info("dWorldManager disabled.");
    }

    //
    // Setup helpers
    //

    private boolean setupDatabase() {
        String type = configManager.getStorageType();

        databaseManager = switch (type.toLowerCase()) {
            case "mysql" -> new MySQLManager(this);
            default -> {
                if (!type.equalsIgnoreCase("sqlite")) {
                    getLogger().warning("Unknown storage type '" + type + "' - falling back to SQLite.");
                }
                yield new SQLiteManager(this);
            }
        };

        try {
            databaseManager.initialize();
            return true;
        } catch (Exception e) {
            getLogger().severe("Database initialization failed: " + e.getMessage());
            return false;
        }
    }

    private void registerCommands() {
        DWorldManagerCommand handler = new DWorldManagerCommand(this);
        var cmd = getCommand("dworldmanager");
        if (cmd != null) {
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }
    }

    //
    // Getters
    //

    public ConfigManager getConfigManager()     { return configManager; }
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public MigrationManager getMigrationManager() { return migrationManager; }
    public BlockFreezeService getBlockFreezeService() { return blockFreezeService; }
    public PlacedBlockTracker getPlacedBlockTracker() { return placedBlockTracker; }
    public ContainerTaintService getContainerTaintService() { return containerTaintService; }
    public ContainerClearService getContainerClearService() { return containerClearService; }
}
