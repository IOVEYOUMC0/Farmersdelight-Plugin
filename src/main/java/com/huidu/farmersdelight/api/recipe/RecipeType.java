package com.huidu.farmersdelight.api.recipe;

import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;

@ApiStatus.OverrideOnly
public interface RecipeType {

    String id();

    Component title();

    ItemStack icon();

    List<ViewableRecipe> recipes();

    default ViewableRecipe recipe(String id) {
        if (id == null) {
            return null;
        }
        for (ViewableRecipe recipe : recipes()) {
            if (id.equals(recipe.id())) {
                return recipe;
            }
        }
        return null;
    }

    default RecipeEditor editor() {
        return null;
    }

    default RecipeBookLayout listLayout() {
        return null;
    }

    default RecipeBookLayout detailLayout() {
        return null;
    }

    default String switchTarget() {
        return null;
    }
}
