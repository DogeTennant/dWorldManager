package com.dogetennant.dworldmanager.block;

/**
 * Marks a coordinate where a player placed a block of a material listed in
 * blocks.restricted-materials. Plain blocks (ore, mineral blocks, etc.) have
 * no tile entity, so they can't carry a PersistentDataContainer the way a
 * chest can - this table is the only way to tell "a player put this here"
 * apart from "this generated naturally" for those materials.
 *
 * Only tracked for materials in the restricted-materials list, and removed
 * again when the block is broken, so this stays bounded the same way
 * frozen_blocks does - it's not a universal placement log.
 */
public record PlacedBlock(
        String world,
        int x,
        int y,
        int z,
        String material,
        long placedAt
) {
}
