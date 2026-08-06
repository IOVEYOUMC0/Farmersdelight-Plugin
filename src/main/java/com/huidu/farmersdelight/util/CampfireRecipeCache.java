package com.huidu.farmersdelight.util;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.CampfireRecipe;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class CampfireRecipeCache {
    private final String debugName;
    private final Consumer<Supplier<String>> debug;
    // Material bucket: per accepted input material the recipes whose RecipeChoice already accepts it.
    // Per-find() lookup hits this bucket instead of iterating all N campfire recipes (vanilla ~50, +addons).
    // Built once per rebuild() by probing each recipe's RecipeChoice against every Material — O(N×M) at
    // load (rare), O(1) at query.
    private final AtomicReference<Map<Material, List<CampfireRecipe>>> byMaterial =
            new AtomicReference<>(Map.of());
    // Tracks whether the cache has been built, separate from "is the cache empty": when the server
    // has no campfire recipes, the cache is empty but still counts as built, avoiding a full recipe-table rescan per lookup.
    private final AtomicBoolean built = new AtomicBoolean(false);

    public CampfireRecipeCache(String debugName, Consumer<Supplier<String>> debug) {
        this.debugName = debugName;
        this.debug = debug;
    }

    public CookingRecipe<?> find(ItemStack item) {
        ItemStack recipeInput = normalizeRecipeInput(item);
        if (recipeInput == null) {
            return null;
        }

        if (!built.get()) {
            rebuild();
        }
        // Fast path: walk only the recipes registered for this material. Empty list when no recipe accepts it
        // (the common "is this cookable" probe for non-food items).
        List<CampfireRecipe> bucket = byMaterial.get().get(recipeInput.getType());
        if (bucket == null) {
            debug.accept(() -> "Campfire recipe miss: input=" + ManagerSupport.formatItem(recipeInput));
            return null;
        }
        for (CampfireRecipe cookingRecipe : bucket) {
            if (matches(cookingRecipe, recipeInput)) {
                debug.accept(() -> "Campfire recipe match: input=" + ManagerSupport.formatItem(recipeInput) + ", recipe=" + cookingRecipe.getKey());
                return cookingRecipe;
            }
        }

        debug.accept(() -> "Campfire recipe miss: input=" + ManagerSupport.formatItem(recipeInput));
        return null;
    }

    public void rebuild() {
        List<CampfireRecipe> recipes = new ArrayList<>();
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            try {
                Recipe recipe = it.next();
                if (recipe instanceof CampfireRecipe campfireRecipe) {
                    recipes.add(campfireRecipe);
                }
            } catch (Exception ignored) {
                // Skip recipes that fail to deserialize (e.g. a ShapedRecipe with an empty result
                // registered by another plugin, or a broken datapack recipe).
            }
        }
        byMaterial.set(buildMaterialBucket(recipes));
        built.set(true);
        debug.accept(() -> "Loaded " + recipes.size() + " cached campfire recipes for " + debugName);
    }

    private static Map<Material, List<CampfireRecipe>> buildMaterialBucket(List<CampfireRecipe> recipes) {
        if (recipes.isEmpty()) {
            return Map.of();
        }
        Map<Material, List<CampfireRecipe>> raw = new EnumMap<>(Material.class);
        for (CampfireRecipe recipe : recipes) {
            RecipeChoice choice = recipe.getInputChoice();
            if (choice instanceof RecipeChoice.MaterialChoice materialChoice) {
                for (Material material : materialChoice.getChoices()) {
                    addToBucket(raw, material, recipe);
                }
            } else if (choice instanceof RecipeChoice.ExactChoice exactChoice) {
                // ExactChoice targets a specific ItemStack (custom items / data-component matches). Index by
                // its base Material; matches() then disambiguates by the full stack (multiple custom foods can
                // share one base material and land in the same bucket).
                for (ItemStack stack : exactChoice.getChoices()) {
                    if (stack != null) {
                        addToBucket(raw, stack.getType(), recipe);
                    }
                }
            }
        }
        // Freeze the lists so concurrent find() readers never see a half-built list.
        Map<Material, List<CampfireRecipe>> frozen = new HashMap<>(raw.size());
        for (Map.Entry<Material, List<CampfireRecipe>> e : raw.entrySet()) {
            frozen.put(e.getKey(), List.copyOf(e.getValue()));
        }
        return Map.copyOf(frozen);
    }

    private static void addToBucket(Map<Material, List<CampfireRecipe>> raw, Material material, CampfireRecipe recipe) {
        if (material == null || material.isLegacy() || !material.isItem()) {
            return;
        }
        raw.computeIfAbsent(material, m -> new ArrayList<>()).add(recipe);
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
}

