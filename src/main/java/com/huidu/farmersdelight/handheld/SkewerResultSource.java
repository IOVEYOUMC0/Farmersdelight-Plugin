package com.huidu.farmersdelight.handheld;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves a configured handheld-skewer.results override, then a campfire recipe fallback.
 * No matching source means no cooking. Production converts the resolved result to an item ID.
 */
@ApiStatus.Internal
public interface SkewerResultSource {

    /** The cooked item id for this held stack, or null when nothing cooks it. */
    @Nullable
    String resultId(@Nullable ItemStack held);
}
