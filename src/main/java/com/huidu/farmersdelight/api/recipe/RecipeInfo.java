package com.huidu.farmersdelight.api.recipe;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only snapshot of a FarmersDelight recipe, carrying only Bukkit / java types so it crosses the
 * obfuscation-stable api boundary. Ingredients and tools are id strings in the recipe-file syntax
 * ("ns:id", "#ns:tag", "a|b" for a choice); ItemStack fields are cloned on
 * access so callers cannot mutate the underlying recipe.
 */
public record RecipeInfo(String id, String type, List<String> ingredients, List<String> tools,
                         ItemStack container, List<ItemStack> results, int cookTimeTicks,
                         double experience, String category) {

    /** type() value for cooking-pot recipes. */
    public static final String TYPE_COOKING_POT = "cooking_pot";
    /** type() value for cutting-board recipes. */
    public static final String TYPE_CUTTING_BOARD = "cutting_board";

    public RecipeInfo {
        ingredients = ingredients == null ? List.of() : List.copyOf(ingredients);
        tools = tools == null ? List.of() : List.copyOf(tools);
        results = cloneAll(results);
        container = container == null ? null : container.clone();
    }

    /** The required container/bowl (cooking pot), or null. Cloned. */
    @Override
    public ItemStack container() {
        return container == null ? null : container.clone();
    }

    /** The produced result item(s). Cloned. */
    @Override
    public List<ItemStack> results() {
        return cloneAll(results);
    }

    private static List<ItemStack> cloneAll(List<ItemStack> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<ItemStack> copies = new ArrayList<>(items.size());
        for (ItemStack item : items) {
            copies.add(item == null ? null : item.clone());
        }
        return List.copyOf(copies);
    }
}
