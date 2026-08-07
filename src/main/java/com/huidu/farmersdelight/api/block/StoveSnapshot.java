package com.huidu.farmersdelight.api.block;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

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

    public int occupiedSlots() {
        int count = 0;
        for (ItemStack item : items) {
            if (item != null) {
                count++;
            }
        }
        return count;
    }

    public double progressFraction(int slot) {
        if (slot < 0 || slot >= progressTicks.size() || slot >= cookTimeTicks.size()) {
            return 0.0D;
        }
        int duration = cookTimeTicks.get(slot);
        return duration <= 0 ? 0.0D : Math.min(1.0D, (double) progressTicks.get(slot) / duration);
    }
}
