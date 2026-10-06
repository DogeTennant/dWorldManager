package com.dogetennant.dworldmanager.database;

import com.dogetennant.dworldmanager.block.FrozenBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
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
    void theUnfreezeLogTimeIndexExists() throws Exception {
        assertThat(query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'idx_unfreeze_log_time';"))
                .isEqualTo("idx_unfreeze_log_time");
    }
}
