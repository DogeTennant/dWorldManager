package com.dogetennant.dworldmanager.block;

import java.util.UUID;

/**
 * Audit trail entry for a staff member deliberately unfreezing grandfathered
 * block(s), so the override can be reviewed later.
 *
 * {@code scope} says what was unfrozen (null for entries logged before
 * dWorldManager 1.1.0):
 * <ul>
 *   <li>BLOCK - one block at x, y, z (affectedCount = 1);</li>
 *   <li>REGION - every frozen block from x, y, z to {@code regionEnd} ("x,y,z");</li>
 *   <li>WORLD - the whole world, every material ({@code material = "*"}) or one.</li>
 * </ul>
 * Older bulk entries carry x = y = z = 0 as a sentinel, for a whole world and a
 * region alike.
 */
public record UnfreezeLogEntry(
        int id,
        String world,
        int x,
        int y,
        int z,
        String material,
        int affectedCount,
        UUID staffUuid,
        String staffName,
        long unfrozenAt,
        Scope scope,
        String regionEnd
) {

    public enum Scope { BLOCK, REGION, WORLD }

    /** An entry without a scope, as logged before 1.1.0. */
    public UnfreezeLogEntry(int id, String world, int x, int y, int z, String material, int affectedCount,
                            UUID staffUuid, String staffName, long unfrozenAt) {
        this(id, world, x, y, z, material, affectedCount, staffUuid, staffName, unfrozenAt, null, null);
    }

    /** The stored scope, or null for an old or unknown one. */
    public static Scope scopeOf(String stored) {
        if (stored == null) return null;
        try {
            return Scope.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
