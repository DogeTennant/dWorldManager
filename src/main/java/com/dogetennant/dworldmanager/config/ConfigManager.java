package com.dogetennant.dworldmanager.config;

import com.dogetennant.dworldmanager.DWorldManager;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class ConfigManager {

    private final DWorldManager plugin;

    //  Cached values
    private boolean debug;

    // Storage
    private String storageType;
    private String tablePrefix;
    private String mysqlHost;
    private int mysqlPort;
    private String mysqlDatabase;
    private String mysqlUsername;
    private String mysqlPassword;
    private int mysqlPoolSize;
    private long mysqlConnectionTimeout;
    private long mysqlMaxLifetime;

    // Blocks (permanent grandfathering)
    private Set<Material> restrictedMaterials;
    private double maxScanBorderSize;
    private long scanTickBudgetMillis;
    private long maxRegionVolume;

    // Messages
    private Map<String, String> messages;

    public ConfigManager(DWorldManager plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();

        debug = plugin.getConfig().getBoolean("debug", false);

        storageType            = plugin.getConfig().getString("storage.type", "sqlite");
        tablePrefix             = plugin.getConfig().getString("storage.table-prefix", "");
        mysqlHost               = plugin.getConfig().getString("storage.mysql.host", "localhost");
        mysqlPort               = plugin.getConfig().getInt("storage.mysql.port", 3306);
        mysqlDatabase           = plugin.getConfig().getString("storage.mysql.database", "dworldmanager");
        mysqlUsername           = plugin.getConfig().getString("storage.mysql.username", "root");
        mysqlPassword           = plugin.getConfig().getString("storage.mysql.password", "");
        mysqlPoolSize           = plugin.getConfig().getInt("storage.mysql.pool-size", 10);
        mysqlConnectionTimeout  = plugin.getConfig().getLong("storage.mysql.connection-timeout", 30000);
        mysqlMaxLifetime        = plugin.getConfig().getLong("storage.mysql.max-lifetime", 1800000);

        restrictedMaterials = EnumSet.noneOf(Material.class);
        for (String name : plugin.getConfig().getStringList("blocks.restricted-materials")) {
            Material material = Material.matchMaterial(name);
            if (material == null) {
                plugin.getLogger().warning("Unknown material in blocks.restricted-materials: " + name);
                continue;
            }
            restrictedMaterials.add(material);
        }
        maxScanBorderSize = plugin.getConfig().getDouble("blocks.max-scan-border-size", 20000);
        scanTickBudgetMillis = plugin.getConfig().getLong("blocks.scan-tick-budget-ms", 25);
        maxRegionVolume = plugin.getConfig().getLong("blocks.max-region-volume", 2_000_000);

        messages = new HashMap<>();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("messages");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                messages.put(key, section.getString(key));
            }
        }
    }

    /** Returns the configured message text for this key, or defaultText if not set. Supports "&" colour codes and %placeholder% tokens. */
    public String getMessage(String key, String defaultText) {
        return messages.getOrDefault(key, defaultText);
    }

    //
    // Getters
    //

    public boolean isDebug()                  { return debug; }

    public String getStorageType()            { return storageType; }
    public String getTablePrefix()            { return tablePrefix; }
    public String getMysqlHost()              { return mysqlHost; }
    public int getMysqlPort()                 { return mysqlPort; }
    public String getMysqlDatabase()          { return mysqlDatabase; }
    public String getMysqlUsername()          { return mysqlUsername; }
    public String getMysqlPassword()          { return mysqlPassword; }
    public int getMysqlPoolSize()             { return mysqlPoolSize; }
    public long getMysqlConnectionTimeout()   { return mysqlConnectionTimeout; }
    public long getMysqlMaxLifetime()         { return mysqlMaxLifetime; }

    public Set<Material> getRestrictedMaterials() { return restrictedMaterials; }
    public double getMaxScanBorderSize()          { return maxScanBorderSize; }
    public long getScanTickBudgetMillis()         { return scanTickBudgetMillis; }
    public long getMaxRegionVolume()              { return maxRegionVolume; }
}
