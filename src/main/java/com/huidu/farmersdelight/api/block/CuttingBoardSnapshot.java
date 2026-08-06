package com.huidu.farmersdelight.api.block;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

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

    public boolean occupied() {
        return storedItem != null;
    }
}
