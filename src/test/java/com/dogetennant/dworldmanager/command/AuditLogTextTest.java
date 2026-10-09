package com.dogetennant.dworldmanager.command;

import com.dogetennant.dworldmanager.block.UnfreezeLogEntry;
import com.dogetennant.dworldmanager.block.UnfreezeLogEntry.Scope;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** How {@code /dwm auditlog} describes each unfreeze. */
class AuditLogTextTest {

    private static final UUID STAFF = UUID.fromString("a0b1c2d3-e4f5-4a6b-8c7d-9e0f1a2b3c4d");

    private static UnfreezeLogEntry entry(int x, int y, int z, String material, int count, Scope scope, String regionEnd) {
        return new UnfreezeLogEntry(1, "build", x, y, z, material, count, STAFF, "Alex", 0, scope, regionEnd);
    }

    @Test
    void oneBlockShowsWhereItWas() {
        assertThat(DWorldManagerCommand.describe(entry(0, 0, 0, "DIAMOND_BLOCK", 1, Scope.BLOCK, null)))
                .isEqualTo("1x DIAMOND_BLOCK in 'build' at 0,0,0");
    }

    @Test
    void aRegionShowsBothCorners() {
        assertThat(DWorldManagerCommand.describe(entry(0, 60, 0, "*", 12, Scope.REGION, "10,70,10")))
                .isEqualTo("12x all materials in region 0,60,0 - 10,70,10 of 'build'");
    }

    @Test
    void aWholeWorldSaysSo() {
        assertThat(DWorldManagerCommand.describe(entry(0, 0, 0, "*", 40, Scope.WORLD, null)))
                .isEqualTo("40x all materials in all of 'build'");
        assertThat(DWorldManagerCommand.describe(entry(0, 0, 0, "GOLD_BLOCK", 3, Scope.WORLD, null)))
                .isEqualTo("3x GOLD_BLOCK in all of 'build'");
    }

    @Test
    void entriesFromBefore110AreShownAsBefore() {
        assertThat(DWorldManagerCommand.describe(entry(0, 0, 0, "*", 40, null, null)))
                .isEqualTo("40x all materials in 'build'");
        assertThat(DWorldManagerCommand.describe(entry(4, 5, 6, "DIAMOND_BLOCK", 1, null, null)))
                .isEqualTo("1x DIAMOND_BLOCK in 'build' at 4,5,6");
    }
}
