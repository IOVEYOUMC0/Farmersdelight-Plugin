package com.huidu.farmersdelight.recipe;

import org.bukkit.inventory.ItemStack;

import java.util.List;

public record CookingPotRecipe(String id, List<RecipeIngredient> ingredients, ItemStack container,
                               boolean needsContainer, ItemStack result, float experience, int cookTime,
                               String category) {
    public String getId() {
        return id;
    }

    public List<RecipeIngredient> getIngredients() {
        return ingredients;
    }

    public ItemStack getContainer() {
        return container;
    }

    public boolean getNeedsContainer() {
        return needsContainer;
    }

    public ItemStack getResult() {
        return result;
    }

    public float getExperience() {
        return experience;
    }

    public int getCookTime() {
        return cookTime;
    }

    public String getCategory() {
        return category;
    }
}
