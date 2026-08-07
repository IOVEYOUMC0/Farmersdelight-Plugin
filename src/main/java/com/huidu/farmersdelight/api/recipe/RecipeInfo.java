package com.huidu.farmersdelight.api.recipe;

import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public record RecipeInfo(String id, String type, List<String> ingredients, List<String> tools,
                         ItemStack container, List<ItemStack> results, int cookTimeTicks,
                         double experience, String category) {

    public static final String TYPE_COOKING_POT = "cooking_pot";
    public static final String TYPE_CUTTING_BOARD = "cutting_board";

    public RecipeInfo {
        ingredients = ingredients == null ? List.of() : List.copyOf(ingredients);
        tools = tools == null ? List.of() : List.copyOf(tools);
        results = cloneAll(results);
        container = container == null ? null : container.clone();
    }

    @Override
    public ItemStack container() {
        return container == null ? null : container.clone();
    }

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
            copies.add(ItemUtils.cloneOrNull(item));
        }
        return List.copyOf(copies);
    }
}
