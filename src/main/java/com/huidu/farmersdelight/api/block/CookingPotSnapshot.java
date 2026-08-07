package com.huidu.farmersdelight.api.block;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

@ApiStatus.Experimental
public record CookingPotSnapshot(Location location,
                                 List<ItemStack> ingredients,
                                 ItemStack container,
                                 ItemStack mealDisplay,
                                 ItemStack output,
                                 String recipeId,
                                 int progressTicks,
                                 int cookTimeTicks,
                                 int remainingTicks,
                                 boolean heated) {

    public CookingPotSnapshot {
        location = location == null ? null : location.clone();
        ingredients = SnapshotItems.copyList(ingredients);
        container = SnapshotItems.copy(container);
        mealDisplay = SnapshotItems.copy(mealDisplay);
        output = SnapshotItems.copy(output);
    }

    @Override
    public Location location() {
        return location == null ? null : location.clone();
    }

    @Override
    public List<ItemStack> ingredients() {
        return SnapshotItems.copyList(ingredients);
    }

    @Override
    public ItemStack container() {
        return SnapshotItems.copy(container);
    }

    @Override
    public ItemStack mealDisplay() {
        return SnapshotItems.copy(mealDisplay);
    }

    @Override
    public ItemStack output() {
        return SnapshotItems.copy(output);
    }

    public boolean cooking() {
        return recipeId != null;
    }

    public double progressFraction() {
        return cookTimeTicks <= 0 ? 0.0D : Math.min(1.0D, (double) progressTicks / cookTimeTicks);
    }
}
