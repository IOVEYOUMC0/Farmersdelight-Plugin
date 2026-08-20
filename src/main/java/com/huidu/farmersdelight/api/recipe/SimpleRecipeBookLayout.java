package com.huidu.farmersdelight.api.recipe;

import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * A plain-data RecipeBookLayout: an addon builds one straight from its own config (title, row
 * count, the character grid, the legend mapping each character to an ingredient/recipe key, and any
 * decoration items). Shared here so addons don't each re-declare the same trivial record.
 */
public record SimpleRecipeBookLayout(Component title, int rows, List<String> layout,
                                     Map<Character, String> legend, Map<String, ItemStack> decorations)
        implements RecipeBookLayout {
}
