package com.dogetennant.dworldmanager.database;

import com.dogetennant.dworldmanager.DWorldManager;
import com.dogetennant.dworldmanager.block.FrozenBlock;
import com.dogetennant.dworldmanager.block.PlacedBlock;
import com.dogetennant.dworldmanager.block.UnfreezeLogEntry;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code /dwm migrate}: copies data.db into the active MySQL database. */
class MigrationManagerTest {

    private static final UUID STAFF = UUID.fromString("a0b1c2d3-e4f5-4a6b-8c7d-9e0f1a2b3c4d");

    @TempDir
    Path dataFolder;

    private DWorldManager plugin;
    private MySQLManager mysql;
    private final CommandSender sender = mock(CommandSender.class);

    @BeforeAll
    static void scheduler() {
        TestPlugin.installImmediateScheduler();
    }

    @BeforeEach
    void setUp() throws Exception {
        plugin = TestPlugin.mockPlugin(dataFolder, "dwm_");
        mysql = TestPlugin.h2MySql(plugin);
        when(plugin.getDatabaseManager()).thenReturn(mysql);
    }

    @AfterEach
    void tearDown() {
        mysql.shutdown();
    }

    /** Writes a data.db with two frozen blocks, one log entry and one placed block. */
    private void sqliteWithData() throws Exception {
        SQLiteManager sqlite = new SQLiteManager(plugin);
        sqlite.initialize();
        sqlite.freezeBlock(new FrozenBlock("world", 1, 2, 3, "DIAMOND_ORE", 10));
        sqlite.freezeBlock(new FrozenBlock("world", 4, 5, 6, "GOLD_BLOCK", 20));
        sqlite.logUnfreeze(new UnfreezeLogEntry(0, "world", 7, 8, 9, "EMERALD_ORE", 1, STAFF, "Admin", 30));
        sqlite.recordPlacedBlock(new PlacedBlock("world", 10, 11, 12, "IRON_ORE", 40));
        sqlite.shutdown();
    }

    @Test
    void copiesEveryTableAndReportsTheCounts() throws Exception {
        sqliteWithData();

        new MigrationManager(plugin).migrate(sender);

        assertThat(mysql.getAllFrozenBlocks()).containsExactlyInAnyOrder(
                new FrozenBlock("world", 1, 2, 3, "DIAMOND_ORE", 10),
                new FrozenBlock("world", 4, 5, 6, "GOLD_BLOCK", 20));
        assertThat(mysql.getUnfreezeLog(10)).singleElement().usingRecursiveComparison().ignoringFields("id")
                .isEqualTo(new UnfreezeLogEntry(0, "world", 7, 8, 9, "EMERALD_ORE", 1, STAFF, "Admin", 30));
        assertThat(mysql.getAllPlacedBlocks()).containsExactly(new PlacedBlock("world", 10, 11, 12, "IRON_ORE", 40));
        verify(sender).sendMessage("Migration complete: 2 frozen block(s), 1 unfreeze log entr(y/ies), "
                + "1 placed block record(s) migrated.");
    }

    @Test
    void theSqliteFileIsKept() throws Exception {
        sqliteWithData();

        new MigrationManager(plugin).migrate(sender);

        assertThat(dataFolder.resolve("data.db")).isRegularFile();
    }

    /** docs/problems/dworldmanager-migrate-twice-duplicates-log.md */
    @Test
    void migratingTwiceCopiesNothingTwice() throws Exception {
        sqliteWithData();

        new MigrationManager(plugin).migrate(sender);
        new MigrationManager(plugin).migrate(sender);

        assertThat(mysql.getAllFrozenBlocks()).hasSize(2);
        assertThat(mysql.getAllPlacedBlocks()).hasSize(1);
        assertThat(mysql.getUnfreezeLog(10)).hasSize(1);
        verify(sender).sendMessage("Migration complete: 2 frozen block(s), 0 unfreeze log entr(y/ies) "
                + "(1 already in MySQL), 1 placed block record(s) migrated.");
    }

    @Test
    void aDifferentEntryOfTheSameBlockIsStillCopied() throws Exception {
        sqliteWithData();
        new MigrationManager(plugin).migrate(sender);
        SQLiteManager sqlite = new SQLiteManager(plugin);
        sqlite.initialize();
        sqlite.logUnfreeze(new UnfreezeLogEntry(0, "world", 7, 8, 9, "EMERALD_ORE", 1, STAFF, "Admin", 31));
        sqlite.shutdown();

        new MigrationManager(plugin).migrate(sender);

        assertThat(mysql.getUnfreezeLog(10)).extracting(UnfreezeLogEntry::unfrozenAt).containsExactly(31L, 30L);
    }

    @Test
    void whatAnUnfreezeCoveredIsCopiedToo() throws Exception {
        SQLiteManager sqlite = new SQLiteManager(plugin);
        sqlite.initialize();
        sqlite.logUnfreeze(new UnfreezeLogEntry(0, "world", 0, 60, 0, "*", 12, STAFF, "Admin", 50,
                UnfreezeLogEntry.Scope.REGION, "10,70,10"));
        sqlite.shutdown();

        new MigrationManager(plugin).migrate(sender);

        assertThat(mysql.getUnfreezeLog(10)).singleElement()
                .extracting(UnfreezeLogEntry::scope, UnfreezeLogEntry::regionEnd)
                .containsExactly(UnfreezeLogEntry.Scope.REGION, "10,70,10");
    }

    @Test
    void refusesWhenTheActiveStorageIsNotMySql() throws Exception {
        sqliteWithData();
        SQLiteManager active = new SQLiteManager(plugin);
        when(plugin.getDatabaseManager()).thenReturn(active);

        new MigrationManager(plugin).migrate(sender);

        verify(sender).sendMessage("Migration aborted: storage.type must be 'mysql' in config.yml to migrate into.");
        assertThat(mysql.getAllFrozenBlocks()).isEmpty();
    }

    @Test
    void saysSoWhenThereIsNoDataDb() {
        new MigrationManager(plugin).migrate(sender);

        verify(sender).sendMessage("No SQLite data.db file found - nothing to migrate.");
    }
}
