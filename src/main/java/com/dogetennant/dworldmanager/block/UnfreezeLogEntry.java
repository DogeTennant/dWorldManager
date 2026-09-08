package com.dogetennant.dworldmanager.block;

import java.util.UUID;

/**
 * Audit trail entry for a staff member deliberately unfreezing grandfathered
 * block(s), so the override can be reviewed later.
 *
 * Single-block unfreezes carry the real coordinates and affectedCount = 1.
 * Bulk unfreezes (whole world, or every block of one material in a world)
 * carry x = y = z = 0 as a sentinel and affectedCount = however many blocks
 * were actually unfrozen, with material = "*" for an unrestricted, whole-world
 * unfreeze.
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
        long unfrozenAt
) {
}
