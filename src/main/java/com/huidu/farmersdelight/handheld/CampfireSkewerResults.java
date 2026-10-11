package com.huidu.farmersdelight.handheld;

import com.huidu.farmersdelight.util.CampfireRecipeCache;
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * Resolves configured skewer overrides before the shared campfire recipe cache.
 * A missing recipe supplies no result, so the caller refuses to start cooking.
 */
final class CampfireSkewerResults implements SkewerResultSource {

    private final Supplier<SkewerResultTable> overrides;
    private final Supplier<CampfireRecipeCache> campfireRecipes;

    CampfireSkewerResults(Supplier<SkewerResultTable> overrides, Supplier<CampfireRecipeCache> campfireRecipes) {
        this.overrides = overrides;
        this.campfireRecipes = campfireRecipes;
    }

    @Override
    @Nullable
    public String resultId(@Nullable ItemStack held) {
        if (held == null || held.getType().isAir()) {
            return null;
        }
        String rawId = ItemUtils.resolveItemId(held);
        if (rawId != null) {
            String configured = overrides.get() == null ? null : overrides.get().resolve(rawId);
            if (configured != null) {
                return configured;
            }
        }
        CampfireRecipeCache cache = campfireRecipes.get();
        if (cache == null) {
            return null;
        }
        CookingRecipe<?> recipe = cache.find(held);
        if (recipe == null || recipe.getResult() == null || recipe.getResult().getType().isAir()) {
            return null;
        }
        return ItemUtils.resolveItemId(recipe.getResult());
    }
}
