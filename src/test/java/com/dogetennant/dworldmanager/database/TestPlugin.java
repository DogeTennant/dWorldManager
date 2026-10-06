package com.dogetennant.dworldmanager.database;

import com.dogetennant.dworldmanager.DWorldManager;
import com.dogetennant.dworldmanager.config.ConfigManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.h2.jdbcx.JdbcDataSource;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Test doubles: a mocked plugin with just what the database classes use, and an H2 "MySQL". */
final class TestPlugin {

    private TestPlugin() {
    }

    /** A plugin whose data folder is {@code dataFolder} and whose tables use {@code prefix}. */
    static DWorldManager mockPlugin(Path dataFolder, String prefix) {
        ConfigManager config = mock(ConfigManager.class);
        when(config.getTablePrefix()).thenReturn(prefix);

        DWorldManager plugin = mock(DWorldManager.class);
        when(plugin.getConfigManager()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("dWorldManager-test"));
        return plugin;
    }

    /**
     * A MySQLManager connected to a fresh in-memory H2 database in MySQL mode, with its tables
     * created. {@code initialize()} is skipped because it builds a {@code jdbc:mysql:} URL.
     */
    static MySQLManager h2MySql(DWorldManager plugin) throws Exception {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:dwm" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");

        HikariConfig hikari = new HikariConfig();
        hikari.setDataSource(uniqueKeyNamesPerDatabase(h2));
        hikari.setMaximumPoolSize(2);

        MySQLManager manager = new MySQLManager(plugin);
        manager.dataSource = new HikariDataSource(hikari);
        manager.createTables();
        return manager;
    }

    private static final Pattern UNIQUE_KEY = Pattern.compile("UNIQUE KEY (\\w+) \\(");
    private static final AtomicInteger KEY_NAMES = new AtomicInteger();

    /**
     * MySQL scopes index names to their table, H2 to the whole database, so the plugin's
     * {@code UNIQUE KEY location} on two tables fails on H2 only. This renames such keys
     * ({@code location_1}, ...) in DDL statements on the way to H2; all other SQL passes unchanged.
     */
    private static DataSource uniqueKeyNamesPerDatabase(JdbcDataSource h2) {
        return (DataSource) Proxy.newProxyInstance(TestPlugin.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (dsProxy, dsMethod, dsArgs) -> {
                    Object result = invoke(h2, dsMethod, dsArgs);
                    if (!(result instanceof Connection con)) {
                        return result;
                    }
                    return Proxy.newProxyInstance(TestPlugin.class.getClassLoader(), new Class<?>[] {Connection.class},
                            (conProxy, conMethod, conArgs) -> {
                                Object made = invoke(con, conMethod, conArgs);
                                if (!(made instanceof Statement stmt) || made instanceof PreparedStatement) {
                                    return made;
                                }
                                return Proxy.newProxyInstance(TestPlugin.class.getClassLoader(),
                                        new Class<?>[] {Statement.class}, (stProxy, stMethod, stArgs) -> {
                                            if (stArgs != null && stArgs.length > 0 && stArgs[0] instanceof String sql
                                                    && sql.contains("CREATE TABLE")) {
                                                stArgs[0] = UNIQUE_KEY.matcher(sql).replaceAll(match ->
                                                        "UNIQUE KEY " + match.group(1) + "_" + KEY_NAMES.incrementAndGet() + " (");
                                            }
                                            return invoke(stmt, stMethod, stArgs);
                                        });
                            });
                });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /**
     * Installs a mocked Bukkit server (once per test JVM) whose scheduler runs every task
     * immediately on the calling thread, so async code can be asserted on directly.
     */
    static synchronized void installImmediateScheduler() {
        if (Bukkit.getServer() != null) {
            return;
        }
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTaskAsynchronously(any(Plugin.class), any(Runnable.class))).thenAnswer(call -> {
            call.getArgument(1, Runnable.class).run();
            return null;
        });
        when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenAnswer(call -> {
            call.getArgument(1, Runnable.class).run();
            return null;
        });

        Server server = mock(Server.class);
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.getLogger()).thenReturn(Logger.getLogger("server-test"));
        // Bukkit.setServer() would ask Paper for server build info, which only a real server has
        try {
            Field field = Bukkit.class.getDeclaredField("server");
            field.setAccessible(true);
            field.set(null, server);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot install the test server", e);
        }
    }
}
