package com.huidu.farmersdelight.util;

import org.bukkit.Bukkit;
import org.bukkit.inventory.CampfireRecipe;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class CampfireRecipeCache {
    private final String debugName;
    private final Consumer<String> debug;
    private final AtomicReference<List<CampfireRecipe>> cache = new AtomicReference<>(List.of());

    public CampfireRecipeCache(String debugName, Consumer<String> debug) {
        this.debugName = debugName;
        this.debug = debug;
    }

    public CookingRecipe<?> find(ItemStack item) {
        ItemStack recipeInput = normalizeRecipeInput(item);
        if (recipeInput == null) {
            return null;
        }

        for (CampfireRecipe cookingRecipe : getRecipes()) {
            if (matches(cookingRecipe, recipeInput)) {
                debug.accept("Campfire recipe match: input=" + formatItem(recipeInput) + ", recipe=" + cookingRecipe.getKey());
                return cookingRecipe;
            }
        }

        debug.accept("Campfire recipe miss: input=" + formatItem(recipeInput));
        return null;
    }

    public void rebuild() {
        List<CampfireRecipe> recipes = new ArrayList<>();
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe recipe = it.next();
            if (recipe instanceof CampfireRecipe campfireRecipe) {
                recipes.add(campfireRecipe);
            }
        }
        cache.set(List.copyOf(recipes));
        debug.accept("Loaded " + recipes.size() + " cached campfire recipes for " + debugName);
    }

    private List<CampfireRecipe> getRecipes() {
        List<CampfireRecipe> recipes = cache.get();
        if (!recipes.isEmpty()) {
            return recipes;
        }

        rebuild();
        return cache.get();
    }

    private boolean matches(CampfireRecipe recipe, ItemStack input) {
        try {
            if (recipe.getInputChoice() == null) {
                return false;
            }

            if (recipe.getInputChoice().test(input)) {
                return true;
            }

            return recipe.getInputChoice().test(new ItemStack(input.getType()));
        } catch (Exception ignored) {
            return false;
        }
    }

    private ItemStack normalizeRecipeInput(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }

        ItemStack normalized = item.clone();
        normalized.setAmount(1);
        return normalized;
    }

    private String formatItem(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "air";
        }
        return item.getType() + "x" + item.getAmount();
    }
}

