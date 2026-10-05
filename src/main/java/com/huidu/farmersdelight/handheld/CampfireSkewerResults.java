package com.huidu.farmersdelight.handheld;

import com.huidu.farmersdelight.util.CampfireRecipeCache;
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * The production SkewerResultSource: the configured override table first, then the same campfire
 * recipe cache the skillet uses (one shared instance, one rebuild lifecycle).
 *
 *
 * Upstream parity on the miss: no recipe means no result, and the caller then refuses to start a session.
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
