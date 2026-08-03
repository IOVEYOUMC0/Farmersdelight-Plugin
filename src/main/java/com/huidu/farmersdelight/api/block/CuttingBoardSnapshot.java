package com.huidu.farmersdelight.api.block;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Immutable read-only view of one cutting board, taken by
 * FarmersDelightBlocks#cuttingBoard(org.bukkit.block.Block). A cutting board holds at most one
 * stack. Nothing here writes back.
 *
 * location the board's block location (block-aligned corner)
 * storedItem the stack lying on the board, or null when the board is empty
 * carved whether the stored item is displayed in the "carved" (tool) pose rather than flat
 */
@ApiStatus.Experimental
public record CuttingBoardSnapshot(Location location, ItemStack storedItem, boolean carved) {

    public CuttingBoardSnapshot {
        location = location == null ? null : location.clone();
        storedItem = SnapshotItems.copy(storedItem);
    }

    @Override
    public Location location() {
        return location == null ? null : location.clone();
    }

    @Override
    public ItemStack storedItem() {
        return SnapshotItems.copy(storedItem);
    }

    /** True when the board currently holds an item. */
    public boolean occupied() {
        return storedItem != null;
    }
}
