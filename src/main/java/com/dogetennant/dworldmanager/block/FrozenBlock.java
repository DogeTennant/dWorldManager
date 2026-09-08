package com.dogetennant.dworldmanager.block;

/**
 * A restricted-material block that existed in the build world at wipe time
 * and can no longer be broken until a staff member unfreezes it.
 */
public record FrozenBlock(
        String world,
        int x,
        int y,
        int z,
        String material,
        long frozenAt
) {
}
