package com.huidu.farmersdelight.api.recipe;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.recipe.CookingPotRecipe;
import com.huidu.farmersdelight.recipe.CuttingBoardRecipe;
import com.huidu.farmersdelight.recipe.CuttingBoardRecipeManager;
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

    // --- item <-> recipe cross-reference (JEI-style navigation) -------------------------
    // The typeId values below come from RecipeStationType.discoveryTypeId() so they can drive
    // FarmersDelightRecipeDiscovery / JumpTarget navigation directly. Optional station filters limit
    // the scan to the given working stations; omitting them searches every station.

    // Every recipe whose result is this item (cooking pot then cutting board), resolved through the
    // managers' O(1) reverse result index instead of a full recipe scan.
    public static List<JumpTarget> findRecipesProducing(ItemStack item, RecipeStationType... stations) {
        List<JumpTarget> targets = new ArrayList<>();
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null || item == null || item.getType().isAir()) {
            return targets;
        }
        if (includes(RecipeStationType.COOKING_POT, stations)) {
            for (CookingPotRecipe recipe : plugin.getCookingPotRecipes().getRecipesProducing(item)) {
                targets.add(new JumpTarget(RecipeStationType.COOKING_POT.discoveryTypeId(), recipe.getId()));
            }
        }
        if (includes(RecipeStationType.CUTTING_BOARD, stations)) {
            for (CuttingBoardRecipe recipe : plugin.getCuttingBoardRecipes().getRecipesProducing(item)) {
                targets.add(new JumpTarget(RecipeStationType.CUTTING_BOARD.discoveryTypeId(), recipe.getId()));
            }
        }
        return targets;
    }

    // Back-compat overload: search every station.
    public static List<JumpTarget> findRecipesProducing(ItemStack item) {
        return findRecipesProducing(item, RecipeStationType.values());
    }

    // Every recipe that consumes this item as an ingredient (cooking-pot ingredient or cutting-board input).
    public static List<JumpTarget> findRecipesUsing(ItemStack item, RecipeStationType... stations) {
        List<JumpTarget> targets = new ArrayList<>();
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin == null || item == null || item.getType().isAir()) {
            return targets;
        }
        if (includes(RecipeStationType.COOKING_POT, stations)) {
            for (CookingPotRecipe recipe : plugin.getCookingPotRecipes().getSortedRecipes(null)) {
                for (RecipeIngredient ingredient : recipe.getIngredients()) {
                    if (plugin.getCookingPotRecipes().matchesIngredient(item, ingredient)) {
                        targets.add(new JumpTarget(RecipeStationType.COOKING_POT.discoveryTypeId(), recipe.getId()));
                        break;
                    }
                }
            }
        }
        if (includes(RecipeStationType.CUTTING_BOARD, stations)) {
            CuttingBoardRecipeManager board = plugin.getCuttingBoardRecipes();
            for (CuttingBoardRecipe recipe : board.getSortedRecipes()) {
                if (board.matchesIngredient(item, recipe.getInput())) {
                    targets.add(new JumpTarget(RecipeStationType.CUTTING_BOARD.discoveryTypeId(), recipe.getId()));
                }
            }
        }
        return targets;
    }

    // Back-compat overload: search every station.
    public static List<JumpTarget> findRecipesUsing(ItemStack item) {
        return findRecipesUsing(item, RecipeStationType.values());
    }

    // Empty (none passed) means "all stations", so includes() returns true for everything.
    private static boolean includes(RecipeStationType target, RecipeStationType[] filter) {
        if (filter == null) {
            return true;
        }
        for (RecipeStationType candidate : filter) {
            if (candidate == target) {
                return true;
            }
        }
        return false;
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
