package com.huidu.farmersdelight.api.recipe;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.recipe.CookingPotRecipe;
import com.huidu.farmersdelight.recipe.CuttingBoardRecipe;
import com.huidu.farmersdelight.recipe.RecipeIngredient;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.List;

@ApiStatus.NonExtendable
public final class FarmersDelightRecipes {

    private FarmersDelightRecipes() {
    }

    public static boolean matchesCookingPot(List<ItemStack> inputs, ItemStack container) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null || inputs == null) {
            return false;
        }
        return plugin.getCookingPotRecipes().matchRecipe(inputs, container) != null;
    }

    public static ItemStack cookingPotResult(List<ItemStack> inputs, ItemStack container) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null || inputs == null) {
            return null;
        }
        CookingPotRecipe recipe = plugin.getCookingPotRecipes().matchRecipe(inputs, container);
        if (recipe == null) {
            return null;
        }
        // Clone the shared recipe result before handing it to a caller: getResult() returns the live stack
        // stored inside the manager's recipe, so a caller mutating it (setAmount/setType) would corrupt
        // every future cook of that recipe. Mirrors cuttingBoardResults / RecipeInfo defensive cloning.
        ItemStack result = recipe.getResult();
        return result == null ? null : result.clone();
    }

    public static boolean hasCuttingBoardRecipe(ItemStack input) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        return plugin != null && plugin.getCuttingBoardRecipes().hasAnyRecipeFor(input);
    }

    public static List<ItemStack> cuttingBoardResults(ItemStack input, ItemStack tool) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null) {
            return List.of();
        }
        CuttingBoardRecipe recipe = plugin.getCuttingBoardRecipes().matchRecipe(input, tool);
        if (recipe == null) {
            return List.of();
        }
        List<ItemStack> results = new ArrayList<>();
        for (CuttingBoardRecipe.ResultEntry entry : recipe.getResults()) {
            if (entry.getItem() != null) {
                results.add(entry.getItem().clone());
            }
        }
        return results;
    }

    public static List<String> cookingPotRecipeIds() {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null) {
            return List.of();
        }
        return new ArrayList<>(plugin.getCookingPotRecipes().getRecipes().keySet());
    }

    public static RecipeInfo cookingPotRecipe(String id) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null || id == null) {
            return null;
        }
        CookingPotRecipe recipe = plugin.getCookingPotRecipes().getRecipe(id);
        if (recipe == null) {
            return null;
        }
        List<String> ingredients = new ArrayList<>();
        for (RecipeIngredient ingredient : recipe.getIngredients()) {
            ingredients.add(ingredientToString(ingredient));
        }
        List<ItemStack> results = recipe.getResult() == null ? List.of() : List.of(recipe.getResult());
        return new RecipeInfo(recipe.getId(), RecipeInfo.TYPE_COOKING_POT, ingredients, List.of(),
                recipe.getContainer(), results, recipe.getCookTime(),
                recipe.getExperience(), recipe.getCategory());
    }

    public static List<String> cuttingBoardRecipeIds() {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null) {
            return List.of();
        }
        return new ArrayList<>(plugin.getCuttingBoardRecipes().getRecipes().keySet());
    }

    public static RecipeInfo cuttingBoardRecipe(String id) {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null || id == null) {
            return null;
        }
        CuttingBoardRecipe recipe = plugin.getCuttingBoardRecipes().getRecipe(id);
        if (recipe == null) {
            return null;
        }
        List<String> tools = new ArrayList<>();
        for (CuttingBoardRecipe.ToolRequirement tool : recipe.getTools()) {
            if (tool != null && tool.getKey() != null) {
                tools.add("#" + tool.getKey());
            }
        }
        List<ItemStack> results = new ArrayList<>();
        for (CuttingBoardRecipe.ResultEntry entry : recipe.getResults()) {
            if (entry.getItem() != null) {
                results.add(entry.getItem());
            }
        }
        return new RecipeInfo(recipe.getId(), RecipeInfo.TYPE_CUTTING_BOARD,
                List.of(ingredientToString(recipe.getInput())), tools,
                null, results, 0, 0.0d, null);
    }

    private static String ingredientToString(RecipeIngredient ingredient) {
        if (ingredient instanceof RecipeIngredient.Item item) {
            return String.valueOf(item.key());
        }
        if (ingredient instanceof RecipeIngredient.Tag tag) {
            return "#" + tag.key();
        }
        if (ingredient instanceof RecipeIngredient.Choice choice) {
            List<String> parts = new ArrayList<>();
            for (RecipeIngredient option : choice.options()) {
                parts.add(ingredientToString(option));
            }
            return String.join("|", parts);
        }
        return "";
    }
}
