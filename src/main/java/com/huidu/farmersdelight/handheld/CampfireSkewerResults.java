package com.huidu.farmersdelight.handheld;

import com.huidu.farmersdelight.util.CampfireRecipeCache;
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Resolves configured skewer overrides before the shared campfire recipe cache.
 * A missing recipe supplies no result, so the caller refuses to start cooking.
 */
final class CampfireSkewerResults implements SkewerResultSource {

    static final String HAND_COOKABLE_TAG = "farmersdelight:hand_cookable";

    private final Predicate<ItemStack> handCookable;
    private final Supplier<SkewerResultTable> overrides;
    private final Supplier<CampfireRecipeCache> campfireRecipes;

    CampfireSkewerResults(Supplier<SkewerResultTable> overrides, Supplier<CampfireRecipeCache> campfireRecipes,
                          Predicate<ItemStack> handCookable) {
        this.handCookable = handCookable;
        this.overrides = overrides;
        this.campfireRecipes = campfireRecipes;
    }

    @Override
    @Nullable
    public String resultId(@Nullable ItemStack held) {
        if (held == null || held.isEmpty() || !handCookable.test(held)) {
            return null;
        }
        String rawId = ItemUtils.resolveItemId(held);
        if (rawId != null) {
            SkewerResultTable table = overrides.get();
            String configured = table == null ? null : table.resolve(rawId);
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
