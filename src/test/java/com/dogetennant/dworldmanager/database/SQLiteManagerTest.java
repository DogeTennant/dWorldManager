package com.dogetennant.dworldmanager.database;

import com.dogetennant.dworldmanager.block.FrozenBlock;
import com.dogetennant.dworldmanager.block.UnfreezeLogEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/** The contract against the real SQLite file {@code data.db}, plus what only SQLite does. */
class SQLiteManagerTest extends DatabaseManagerContractTest {

    @TempDir
    Path dataFolder;

    private int opened;

    @Override
    protected DatabaseManager open(String prefix) throws Exception {
        // a second database in the same test gets its own folder, so it has its own data.db
        Path folder = opened++ == 0 ? dataFolder : dataFolder.resolve("second");
        SQLiteManager manager = new SQLiteManager(TestPlugin.mockPlugin(folder, prefix));
        manager.initialize();
        return manager;
    }

    private String query(String sql) throws Exception {
        try (Connection con = db.dataSource.getConnection();
             Statement stmt = con.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @Test
    void theDatabaseIsTheFileDataDbInTheDataFolder() {
        assertThat(dataFolder.resolve("data.db")).isRegularFile();
    }

    @Test
    void dataSurvivesClosingAndReopeningTheFile() throws Exception {
        db.freezeBlock(new FrozenBlock("world", 1, 2, 3, "DIAMOND_ORE", 10));
        db.shutdown();

        SQLiteManager reopened = new SQLiteManager(TestPlugin.mockPlugin(dataFolder, "dwm_"));
        reopened.initialize();
        try {
            assertThat(reopened.getFrozenBlock("world", 1, 2, 3))
                    .isEqualTo(new FrozenBlock("world", 1, 2, 3, "DIAMOND_ORE", 10));
        } finally {
            reopened.shutdown();
        }
    }

    @Test
    void theJournalIsInWalMode() throws Exception {
        assertThat(query("PRAGMA journal_mode;")).isEqualToIgnoringCase("wal");
    }

    @Test
    void aDataDbFromBefore110GetsTheNewLogColumnsAndKeepsItsEntries() throws Exception {
        Path old = Files.createDirectories(dataFolder.resolve("old"));
        try (Connection con = DriverManager.getConnection("jdbc:sqlite:" + old.resolve("data.db"));
             Statement stmt = con.createStatement()) {
            stmt.executeUpdate("""
                CREATE TABLE "unfreeze_log" (
                    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, world TEXT NOT NULL,
                    x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, material TEXT NOT NULL,
                    affected_count INTEGER NOT NULL DEFAULT 1, staff_uuid TEXT NOT NULL,
                    staff_name TEXT NOT NULL, unfrozen_at INTEGER NOT NULL);""");
            stmt.executeUpdate("INSERT INTO \"unfreeze_log\" (world, x, y, z, material, affected_count, staff_uuid,"
                    + " staff_name, unfrozen_at) VALUES ('world', 0, 0, 0, '*', 5, '" + STAFF + "', 'Admin', 100);");
        }

        SQLiteManager upgraded = new SQLiteManager(TestPlugin.mockPlugin(old, ""));
        upgraded.initialize();
        try {
            upgraded.logUnfreeze(new UnfreezeLogEntry(0, "world", 1, 2, 3, "*", 2, STAFF, "Admin", 200,
                    UnfreezeLogEntry.Scope.REGION, "4,5,6"));

            assertThat(upgraded.getUnfreezeLog(10)).extracting(UnfreezeLogEntry::scope, UnfreezeLogEntry::regionEnd)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(UnfreezeLogEntry.Scope.REGION, "4,5,6"),
                            org.assertj.core.groups.Tuple.tuple(null, null));
        } finally {
            upgraded.shutdown();
        }
    }

    @Test
    void theUnfreezeLogTimeIndexExists() throws Exception {
        assertThat(query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'idx_unfreeze_log_time';"))
                .isEqualTo("idx_unfreeze_log_time");
    }
}
