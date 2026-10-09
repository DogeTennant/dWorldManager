package com.dogetennant.dworldmanager.database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * The contract against the MySQL dialect, run on H2 in MySQL mode. That checks the SQL the
 * plugin sends (upserts, {@code INSERT ... ON DUPLICATE KEY}, {@code LIMIT}), not MySQL itself.
 */
class MySQLManagerTest extends DatabaseManagerContractTest {

    @TempDir
    Path dataFolder;

    @Override
    protected DatabaseManager open(String prefix) throws Exception {
        return TestPlugin.h2MySql(TestPlugin.mockPlugin(dataFolder, prefix));
    }

    @Test
    void startingAgainWithTheNewLogColumnsAlreadyThereIsFine() throws Exception {
        // the columns added in 1.1.0 exist now; adding them again must be skipped quietly
        ((MySQLManager) db).createTables();
        ((MySQLManager) db).createTables();
    }
}
