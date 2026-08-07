package com.huidu.farmersdelight.api.block;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Experimental
public record SkilletSnapshot(Location location,
                              ItemStack storedItem,
                              ItemStack skilletItem,
                              String recipeId,
                              int progressTicks,
                              int cookTimeTicks,
                              int remainingTicks,
                              boolean heated,
                              int fireAspectLevel) {

    public SkilletSnapshot {
        location = location == null ? null : location.clone();
        storedItem = SnapshotItems.copy(storedItem);
        skilletItem = SnapshotItems.copy(skilletItem);
    }

    @Override
    public Location location() {
        return location == null ? null : location.clone();
    }

    @Override
    public ItemStack storedItem() {
        return SnapshotItems.copy(storedItem);
    }

    @Override
    public ItemStack skilletItem() {
        return SnapshotItems.copy(skilletItem);
    }

    public boolean cooking() {
        return recipeId != null;
    }

    public double progressFraction() {
        return cookTimeTicks <= 0 ? 0.0D : Math.min(1.0D, (double) progressTicks / cookTimeTicks);
    }
}
