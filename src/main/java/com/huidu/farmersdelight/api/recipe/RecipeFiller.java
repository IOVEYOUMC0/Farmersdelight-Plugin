package com.huidu.farmersdelight.api.recipe;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.OverrideOnly
public interface RecipeFiller {

    boolean fill(Player player, ViewableRecipe recipe);

    default FillOutcome fillDetailed(Player player, ViewableRecipe recipe, boolean fillAll) {
        return fill(player, recipe) ? FillOutcome.FILLED : FillOutcome.NOTHING;
    }

    default boolean onBack(Player player) {
        return false;
    }
}
