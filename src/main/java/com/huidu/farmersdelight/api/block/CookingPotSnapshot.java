package com.huidu.farmersdelight.api.block;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

/**
 * Immutable read-only view of one cooking pot, taken by
 * FarmersDelightBlocks#cookingPot(org.bukkit.block.Block). Nothing here writes back: mutating
 * a returned stack or list has no effect on the pot.
 *
 * @param location      the pot's block location (block-aligned corner)
 * @param ingredients   the ingredient slots in layout order; a null entry means an empty slot
 * @param container     the bowl/bottle slot's contents, or null when empty
 * @param mealDisplay   the finished meal currently shown in the pot, or null when the pot holds none
 * @param output        the item waiting in the output slot, or null when the slot is empty
 * @param recipeId      the recipe currently being cooked, or null when the pot is idle
 * @param progressTicks ticks of progress accumulated toward the current recipe
 * @param cookTimeTicks ticks the current recipe needs in total; 0 when idle and no duration is set
 * @param remainingTicks cookTimeTicks minus progressTicks, floored at 0
 * @param heated        whether a configured heat source (or conductor over one) is under the pot
 */
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

    /** True when the pot has a matched recipe and is accumulating progress toward it. */
    public boolean cooking() {
        return recipeId != null;
    }

    /** Progress as a 0..1 fraction; 0 when the pot is idle or no duration is set. */
    public double progressFraction() {
        return cookTimeTicks <= 0 ? 0.0D : Math.min(1.0D, (double) progressTicks / cookTimeTicks);
    }
}
