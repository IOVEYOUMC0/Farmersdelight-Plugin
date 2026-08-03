package com.huidu.farmersdelight.api.block;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

/**
 * Immutable read-only view of one stove, taken by
 * FarmersDelightBlocks#stove(org.bukkit.block.Block). A stove grills several items at once, so
 * the three lists are parallel and always the same length — one entry per grilling slot, indexed the
 * same way the stove tracks them. Nothing here writes back.
 *
 * location the stove's block location (block-aligned corner)
 * items per-slot food; a null entry means an empty slot
 * progressTicks per-slot ticks of progress accumulated
 * cookTimeTicks per-slot ticks the slot's item needs in total
 * lit whether the stove is burning; an unlit stove makes no progress
 * blockedAbove whether a collision shape above the stove blocks the grilling area
 */
@ApiStatus.Experimental
public record StoveSnapshot(Location location,
                            List<ItemStack> items,
                            List<Integer> progressTicks,
                            List<Integer> cookTimeTicks,
                            boolean lit,
                            boolean blockedAbove) {

    public StoveSnapshot {
        location = location == null ? null : location.clone();
        items = SnapshotItems.copyList(items);
        progressTicks = SnapshotItems.copyInts(progressTicks);
        cookTimeTicks = SnapshotItems.copyInts(cookTimeTicks);
    }

    @Override
    public Location location() {
        return location == null ? null : location.clone();
    }

    @Override
    public List<ItemStack> items() {
        return SnapshotItems.copyList(items);
    }

    /** Number of slots holding food. */
    public int occupiedSlots() {
        int count = 0;
        for (ItemStack item : items) {
            if (item != null) {
                count++;
            }
        }
        return count;
    }

    /** Progress of one slot as a 0..1 fraction; 0 for an out-of-range slot or an unset duration. */
    public double progressFraction(int slot) {
        if (slot < 0 || slot >= progressTicks.size() || slot >= cookTimeTicks.size()) {
            return 0.0D;
        }
        int duration = cookTimeTicks.get(slot);
        return duration <= 0 ? 0.0D : Math.min(1.0D, (double) progressTicks.get(slot) / duration);
    }
}
