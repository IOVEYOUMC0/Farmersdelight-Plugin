package com.huidu.farmersdelight.handheld;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Where a handheld skewer's cooked result comes from.
 *
 *
 * Upstream (HandCookedItem) asks the campfire cooking recipe. This pack also keeps its own
 * handheld-skewer.results table, and the agreed rule is that the table is an override in front of
 * the recipe query: a configured mapping wins, everything else falls through to the campfire recipe, and no
 * recipe at all means no cooking (again upstream behaviour).
 *
 *
 * The seam is item-level so the wiring can be driven offline; the production implementation turns the stored
 * override or the recipe result into an item id.
 */
@ApiStatus.Internal
public interface SkewerResultSource {

    /** The cooked item id for this held stack, or null when nothing cooks it. */
    @Nullable
    String resultId(@Nullable ItemStack held);
}
