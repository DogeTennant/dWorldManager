package com.dogetennant.dworldmanager.database;

import com.dogetennant.dworldmanager.block.FrozenBlock;
import com.dogetennant.dworldmanager.block.PlacedBlock;
import com.dogetennant.dworldmanager.block.UnfreezeLogEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What every storage backend must do the same way. Runs once per dialect
 * ({@link SQLiteManagerTest}, {@link MySQLManagerTest}).
 */
abstract class DatabaseManagerContractTest {

    protected static final UUID STAFF = UUID.fromString("a0b1c2d3-e4f5-4a6b-8c7d-9e0f1a2b3c4d");

    protected DatabaseManager db;

    /** Opens a ready-to-use manager whose tables use {@code prefix}. */
    protected abstract DatabaseManager open(String prefix) throws Exception;

    @BeforeEach
    void openDatabase() throws Exception {
        db = open("dwm_");
    }

    @AfterEach
    void closeDatabase() {
        db.shutdown();
    }

    private static FrozenBlock frozen(String world, int x, int y, int z, String material, long at) {
        return new FrozenBlock(world, x, y, z, material, at);
    }

    protected List<String> tableNames(DatabaseManager manager) throws Exception {
        List<String> names = new ArrayList<>();
        try (Connection con = manager.dataSource.getConnection();
             ResultSet rs = con.getMetaData().getTables(null, null, "%", new String[] {"TABLE"})) {
            while (rs.next()) {
                names.add(rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    //
    // Tables
    //

    @Test
    void tablesCarryThePrefix() throws Exception {
        assertThat(tableNames(db)).contains("dwm_frozen_blocks", "dwm_unfreeze_log", "dwm_placed_blocks");
    }

    @Test
    void anEmptyPrefixGivesPlainTableNames() throws Exception {
        DatabaseManager plain = open("");
        try {
            assertThat(tableNames(plain)).contains("frozen_blocks", "unfreeze_log", "placed_blocks");
        } finally {
            plain.shutdown();
        }
    }

    @Test
    void creatingTheTablesAgainKeepsTheData() throws Exception {
        db.freezeBlock(frozen("world", 1, 2, 3, "DIAMOND_ORE", 10));

        db.createTables();

        assertThat(db.getFrozenBlock("world", 1, 2, 3)).isNotNull();
    }

    @Test
    void shutdownClosesThePool() {
        assertThat(db.isConnected()).isTrue();

        db.shutdown();

        assertThat(db.isConnected()).isFalse();
    }

    //
    // Frozen blocks
    //

    @Test
    void aFrozenBlockIsReadBackExactly() {
        FrozenBlock block = frozen("world", 12, -40, -7, "DIAMOND_ORE", 1_700_000_000_123L);

        db.freezeBlock(block);

        assertThat(db.getFrozenBlock("world", 12, -40, -7)).isEqualTo(block);
    }

    @Test
    void anUnfrozenSpotReadsAsNull() {
        assertThat(db.getFrozenBlock("world", 0, 0, 0)).isNull();
    }

    @Test
    void freezingAnAlreadyFrozenSpotKeepsTheFirstRecord() {
        db.freezeBlock(frozen("world", 1, 1, 1, "DIAMOND_ORE", 100));

        db.freezeBlock(frozen("world", 1, 1, 1, "GOLD_BLOCK", 200));

        assertThat(db.getFrozenBlock("world", 1, 1, 1)).isEqualTo(frozen("world", 1, 1, 1, "DIAMOND_ORE", 100));
        assertThat(db.getAllFrozenBlocks()).hasSize(1);
    }

    @Test
    void batchFreezeSkipsSpotsThatAreAlreadyFrozen() {
        db.freezeBlock(frozen("world", 1, 1, 1, "DIAMOND_ORE", 100));

        db.freezeBlocks(List.of(
                frozen("world", 1, 1, 1, "GOLD_BLOCK", 200),
                frozen("world", 2, 2, 2, "EMERALD_ORE", 200),
                frozen("world", 3, 3, 3, "IRON_BLOCK", 200),
                frozen("world", 3, 3, 3, "COAL_BLOCK", 300)));

        assertThat(db.getAllFrozenBlocks()).containsExactlyInAnyOrder(
                frozen("world", 1, 1, 1, "DIAMOND_ORE", 100),
                frozen("world", 2, 2, 2, "EMERALD_ORE", 200),
                frozen("world", 3, 3, 3, "IRON_BLOCK", 200));
    }

    @Test
    void anEmptyBatchChangesNothing() {
        db.freezeBlocks(List.of());

        assertThat(db.getAllFrozenBlocks()).isEmpty();
    }

    @Test
    void unfreezeBlockSaysWhetherSomethingWasRemoved() {
        db.freezeBlock(frozen("world", 5, 6, 7, "DIAMOND_ORE", 1));

        assertThat(db.unfreezeBlock("world", 5, 6, 7)).isTrue();
        assertThat(db.unfreezeBlock("world", 5, 6, 7)).isFalse();
        assertThat(db.getFrozenBlock("world", 5, 6, 7)).isNull();
    }

    @Test
    void unfreezeRegionIsInclusiveAndTakesTheCornersInAnyOrder() {
        db.freezeBlocks(List.of(
                frozen("world", 0, 0, 0, "DIAMOND_ORE", 1),
                frozen("world", 5, 5, 5, "DIAMOND_ORE", 1),
                frozen("world", 6, 0, 0, "DIAMOND_ORE", 1),
                frozen("other", 1, 1, 1, "DIAMOND_ORE", 1)));

        int removed = db.unfreezeRegion("world", 5, 5, 5, 0, 0, 0);

        assertThat(removed).isEqualTo(2);
        assertThat(db.getAllFrozenBlocks()).containsExactlyInAnyOrder(
                frozen("world", 6, 0, 0, "DIAMOND_ORE", 1),
                frozen("other", 1, 1, 1, "DIAMOND_ORE", 1));
    }

    @Test
    void unfreezeWorldRemovesOnlyThatWorld() {
        db.freezeBlocks(List.of(
                frozen("world", 1, 1, 1, "DIAMOND_ORE", 1),
                frozen("world", 2, 2, 2, "GOLD_BLOCK", 1),
                frozen("other", 1, 1, 1, "DIAMOND_ORE", 1)));

        assertThat(db.unfreezeWorld("world")).isEqualTo(2);
        assertThat(db.getAllFrozenBlocks()).containsExactly(frozen("other", 1, 1, 1, "DIAMOND_ORE", 1));
    }

    @Test
    void unfreezeMaterialRemovesOnlyThatMaterialInThatWorld() {
        db.freezeBlocks(List.of(
                frozen("world", 1, 1, 1, "DIAMOND_ORE", 1),
                frozen("world", 2, 2, 2, "GOLD_BLOCK", 1),
                frozen("other", 1, 1, 1, "DIAMOND_ORE", 1)));

        assertThat(db.unfreezeMaterial("world", "DIAMOND_ORE")).isEqualTo(1);
        assertThat(db.getAllFrozenBlocks()).containsExactlyInAnyOrder(
                frozen("world", 2, 2, 2, "GOLD_BLOCK", 1),
                frozen("other", 1, 1, 1, "DIAMOND_ORE", 1));
    }

    @Test
    void frozenBlocksCanBeListedPerWorld() {
        db.freezeBlocks(List.of(
                frozen("world", 1, 1, 1, "DIAMOND_ORE", 1),
                frozen("other", 1, 1, 1, "DIAMOND_ORE", 1)));

        assertThat(db.getFrozenBlocksInWorld("world")).containsExactly(frozen("world", 1, 1, 1, "DIAMOND_ORE", 1));
        assertThat(db.getFrozenBlocksInWorld("nether")).isEmpty();
    }

    //
    // Placed blocks
    //

    @Test
    void placingAgainOnTheSameSpotRefreshesMaterialAndTime() {
        db.recordPlacedBlock(new PlacedBlock("world", 1, 2, 3, "DIAMOND_ORE", 100));

        db.recordPlacedBlock(new PlacedBlock("world", 1, 2, 3, "GOLD_ORE", 200));

        assertThat(db.getAllPlacedBlocks()).containsExactly(new PlacedBlock("world", 1, 2, 3, "GOLD_ORE", 200));
    }

    @Test
    void removePlacedBlockSaysWhetherSomethingWasRemoved() {
        db.recordPlacedBlock(new PlacedBlock("world", 1, 2, 3, "DIAMOND_ORE", 100));

        assertThat(db.removePlacedBlock("world", 1, 2, 3)).isTrue();
        assertThat(db.removePlacedBlock("world", 1, 2, 3)).isFalse();
        assertThat(db.getAllPlacedBlocks()).isEmpty();
    }

    @Test
    void placedBlocksCanBeListedPerWorld() {
        db.recordPlacedBlock(new PlacedBlock("world", 1, 2, 3, "DIAMOND_ORE", 100));
        db.recordPlacedBlock(new PlacedBlock("other", 1, 2, 3, "DIAMOND_ORE", 100));

        assertThat(db.getPlacedBlocksInWorld("world")).containsExactly(
                new PlacedBlock("world", 1, 2, 3, "DIAMOND_ORE", 100));
    }

    @Test
    void placedAndFrozenBlocksAreSeparate() {
        db.recordPlacedBlock(new PlacedBlock("world", 1, 2, 3, "DIAMOND_ORE", 100));

        assertThat(db.getFrozenBlock("world", 1, 2, 3)).isNull();
    }

    //
    // Unfreeze log
    //

    @Test
    void theUnfreezeLogKeepsEveryFieldAndGivesIds() {
        db.logUnfreeze(new UnfreezeLogEntry(0, "world", 4, 5, 6, "DIAMOND_ORE", 1, STAFF, "Admin", 1000));

        List<UnfreezeLogEntry> log = db.getUnfreezeLog(10);

        assertThat(log).hasSize(1);
        UnfreezeLogEntry entry = log.get(0);
        assertThat(entry.id()).isPositive();
        assertThat(entry).usingRecursiveComparison().ignoringFields("id")
                .isEqualTo(new UnfreezeLogEntry(0, "world", 4, 5, 6, "DIAMOND_ORE", 1, STAFF, "Admin", 1000));
    }

    @Test
    void anUnfreezeKeepsWhatItCovered() {
        db.logUnfreeze(new UnfreezeLogEntry(0, "world", 0, 60, 0, "*", 12, STAFF, "Admin", 1000,
                UnfreezeLogEntry.Scope.REGION, "10,70,10"));
        db.logUnfreeze(new UnfreezeLogEntry(0, "world", 0, 0, 0, "GOLD_BLOCK", 3, STAFF, "Admin", 2000,
                UnfreezeLogEntry.Scope.WORLD, null));

        assertThat(db.getUnfreezeLog(10)).usingRecursiveFieldByFieldElementComparatorIgnoringFields("id")
                .containsExactly(
                        new UnfreezeLogEntry(0, "world", 0, 0, 0, "GOLD_BLOCK", 3, STAFF, "Admin", 2000,
                                UnfreezeLogEntry.Scope.WORLD, null),
                        new UnfreezeLogEntry(0, "world", 0, 60, 0, "*", 12, STAFF, "Admin", 1000,
                                UnfreezeLogEntry.Scope.REGION, "10,70,10"));
    }

    @Test
    void theUnfreezeLogIsNewestFirstAndLimited() {
        db.logUnfreeze(new UnfreezeLogEntry(0, "world", 0, 0, 0, "*", 30, STAFF, "Admin", 1000));
        db.logUnfreeze(new UnfreezeLogEntry(0, "world", 0, 0, 0, "*", 10, STAFF, "Admin", 3000));
        db.logUnfreeze(new UnfreezeLogEntry(0, "world", 0, 0, 0, "*", 20, STAFF, "Admin", 2000));

        assertThat(db.getUnfreezeLog(2)).extracting(UnfreezeLogEntry::unfrozenAt).containsExactly(3000L, 2000L);
    }
}
