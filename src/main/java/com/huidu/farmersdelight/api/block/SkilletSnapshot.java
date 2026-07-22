package com.huidu.farmersdelight.api.block;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Immutable read-only view of one skillet, taken by
 * FarmersDelightBlocks#skillet(org.bukkit.block.Block). A skillet holds at most one stack of
 * food plus the skillet item it was placed from. Nothing here writes back.
 *
 * @param location        the skillet's block location (block-aligned corner)
 * @param storedItem      the food currently in the pan, or null when the pan is empty
 * @param skilletItem     the skillet item the block was placed from (carries its enchantments), or null
 * @param recipeId        the campfire recipe key being cooked, or null when nothing matches
 * @param progressTicks   ticks of progress accumulated toward the current recipe
 * @param cookTimeTicks   ticks the current recipe needs in total
 * @param remainingTicks  cookTimeTicks minus progressTicks, floored at 0
 * @param heated          whether a configured heat source (or conductor over one) is under the skillet
 * @param fireAspectLevel the Fire Aspect level on the skillet item, which shortens the cook time
 */
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

    /** True when the skillet holds food that matches a recipe. Progress only advances while heated. */
    public boolean cooking() {
        return recipeId != null;
    }

    /** Progress as a 0..1 fraction; 0 when no duration is set. */
    public double progressFraction() {
        return cookTimeTicks <= 0 ? 0.0D : Math.min(1.0D, (double) progressTicks / cookTimeTicks);
    }
}
