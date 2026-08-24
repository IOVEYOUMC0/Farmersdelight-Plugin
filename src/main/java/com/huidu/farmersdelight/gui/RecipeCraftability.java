package com.huidu.farmersdelight.gui;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockEntity;
import com.huidu.farmersdelight.api.FarmersDelightApi;
import com.huidu.farmersdelight.api.item.FarmersDelightItems;
import com.huidu.farmersdelight.api.recipe.RecipeType;
import com.huidu.farmersdelight.api.recipe.ViewableRecipe;
import com.huidu.farmersdelight.recipe.CookingPotRecipe;
import com.huidu.farmersdelight.recipe.CuttingBoardRecipe;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

// Pure gameplay queries the recipe-list/detail pages need: which recipes the viewer can actually craft
// right now, and which recipe (if any) produces a clicked display item (linked-recipe navigation). No
// inventory rendering or GUI state -- the gui hands in the viewer plus the pot it was opened at. Kept as
// a stateless helper so craftability is decoupled from the draw/click state machine.
final class RecipeCraftability {

    record LinkedRecipe(String recipeId, boolean cookingPot) {
    }

    // A linked jump that resolves to an addon workstation recipe (keg, BBQ station, ...) instead of FD's
    // own pot/board. The target lives in the addon's RecipeType, so the jump hands off to its book view.
    record LinkedAddonRecipe(RecipeType type, String recipeId) {
    }

    private final FarmersDelightPlugin plugin;
    private final Location cookingPotLocation;
    private final String recipeGroupId;

    RecipeCraftability(FarmersDelightPlugin plugin, Location cookingPotLocation, String recipeGroupId) {
        this.plugin = plugin;
        this.cookingPotLocation = cookingPotLocation;
        this.recipeGroupId = recipeGroupId;
    }

    List<CookingPotRecipe> filterCraftableCookingPotRecipes(List<CookingPotRecipe> recipes, Player player) {
        CookingPotBlockEntity entity = cookingPotLocation == null ? null : CookingPotBlockBehavior.getBlockEntity(cookingPotLocation);
        List<ItemStack> available = getAvailableCookingPotItems(entity, player);
        List<CookingPotRecipe> craftableRecipes = new ArrayList<>();
        for (CookingPotRecipe recipe : recipes) {
            if (canCraftCookingPotRecipe(recipe, entity, available)) {
                craftableRecipes.add(recipe);
            }
        }
        return craftableRecipes;
    }

    List<CuttingBoardRecipe> filterCraftableCuttingBoardRecipes(List<CuttingBoardRecipe> recipes, Player player) {
        List<ItemStack> available = Arrays.stream(player.getInventory().getStorageContents())
                .filter(Objects::nonNull)
                .filter(item -> !item.isEmpty())
                .toList();
        return plugin.getCuttingBoardRecipes().filterCraftable(recipes, available);
    }

    LinkedRecipe findLinkedRecipe(ItemStack item, boolean cookingPotMode) {
        if (item == null || item.getType().isAir()) {
            return null;
        }

        if (cookingPotMode) {
            LinkedRecipe linked = findCookingPotRecipeByResult(item);
            if (linked != null) {
                return linked;
            }
            return findCuttingBoardRecipeByResult(item);
        }

        LinkedRecipe linked = findCuttingBoardRecipeByResult(item);
        if (linked != null) {
            return linked;
        }
        return findCookingPotRecipeByResult(item);
    }

    private LinkedRecipe findCookingPotRecipeByResult(ItemStack item) {
        // O(1) reverse result index instead of scanning every recipe. First match wins, as before.
        for (CookingPotRecipe recipe : plugin.getCookingPotRecipes().getRecipesProducing(item)) {
            if (recipeGroupId == null
                    || plugin.getCookingPotRecipes().getRecipe(recipeGroupId, recipe.getId()) != null
                    || plugin.getCookingPotRecipes().getRecipe(recipe.getId()) != null) {
                return new LinkedRecipe(recipe.getId(), true);
            }
        }
        return null;
    }

    private LinkedRecipe findCuttingBoardRecipeByResult(ItemStack item) {
        for (CuttingBoardRecipe recipe : plugin.getCuttingBoardRecipes().getRecipesProducing(item)) {
            return new LinkedRecipe(recipe.getId(), false);
        }
        return null;
    }

    // Finds an addon workstation recipe (keg, BBQ station, crab trap, ...) whose result matches the item.
    // FD's own pot/board indexes never contain these, so linked jumps fall back here and hand off to the
    // addon RecipeBook view. First registered match wins, matching the pot/board first-match policy.
    LinkedAddonRecipe findLinkedAddonRecipe(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        for (RecipeType type : FarmersDelightApi.get().recipeTypes()) {
            for (ViewableRecipe recipe : type.recipes()) {
                if (recipe == null) {
                    continue;
                }
                ItemStack result = recipe.result();
                if (result != null && !result.getType().isAir()
                        && FarmersDelightItems.isSameItem(item, result)) {
                    return new LinkedAddonRecipe(type, recipe.id());
                }
            }
        }
        return null;
    }

    private boolean canCraftCookingPotRecipe(CookingPotRecipe recipe, CookingPotBlockEntity entity, List<ItemStack> available) {
        if (entity != null && !canRecipeFitCookingPot(recipe, entity)) {
            return false;
        }
        // The container is not part of "can I cook this": the pot cooks from ingredients alone and the bowl is
        // supplied at extraction time, so a meal recipe stays craftable even when the player carries no bowl.
        // Containment question, not the real cook: does the inventory (+ current pot inputs) hold enough of
        // each ingredient, ignoring the unrelated items every inventory carries? canCraft's lenient pass would
        // reject on the first foreign slot, so it can't be used here.
        return plugin.getCookingPotRecipes().containsIngredientsFor(recipe, available);
    }

    private List<ItemStack> getAvailableCookingPotItems(CookingPotBlockEntity entity, Player player) {
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && !item.getType().isAir()) {
                items.add(item.clone());
            }
        }
        if (entity != null) {
            for (ItemStack item : entity.getInventory()) {
                if (item != null && !item.getType().isAir()) {
                    items.add(item.clone());
                }
            }
        }
        return items;
    }

    private boolean canRecipeFitCookingPot(CookingPotRecipe recipe, CookingPotBlockEntity entity) {
        if (recipe == null || entity == null) {
            return false;
        }
        return recipe.getIngredients().size() <= entity.getLayout().inputSlots().length;
    }

}