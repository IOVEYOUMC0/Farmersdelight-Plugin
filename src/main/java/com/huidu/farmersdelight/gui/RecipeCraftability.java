package com.huidu.farmersdelight.gui;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockEntity;
import com.huidu.farmersdelight.api.FarmersDelightApi;
import com.huidu.farmersdelight.api.recipe.IngredientMatchMemo;
import com.huidu.farmersdelight.api.recipe.IngredientMatching;
import com.huidu.farmersdelight.api.recipe.JumpTarget;
import com.huidu.farmersdelight.api.recipe.RecipeType;
import com.huidu.farmersdelight.recipe.CookingPotRecipe;
import com.huidu.farmersdelight.recipe.CuttingBoardRecipe;
import com.huidu.farmersdelight.recipe.RecipeIngredient;
import com.huidu.farmersdelight.recipe.SpecialRecipeRegistry;
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

    String findSpecialRecipe(ItemStack item) {
        SpecialRecipeRegistry registry = plugin.getSpecialRecipeRegistry();
        return registry == null ? null : registry.findLinkedRecipe(item);
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
        // One memo for the whole draw: a page tests the same few ingredient expressions against the same
        // stacks for every recipe, and every test resolves CraftEngine item ids and tags. The container is
        // not part of "can I cook this" -- the pot cooks from ingredients alone and the bowl is supplied at
        // extraction time, so a meal recipe stays craftable even when the player carries no bowl.
        // Containment question, not the real cook: does the inventory (+ current pot inputs) hold enough of
        // each ingredient, ignoring the unrelated items every inventory carries? canCraft's lenient pass
        // would reject on the first foreign slot, so it can't be used here.
        IngredientMatchMemo<ItemStack, RecipeIngredient> matches = IngredientMatchMemo.of(
                plugin.getCookingPotRecipes()::matchesIngredient, RecipeIngredient::stableKey);
        List<CookingPotRecipe> craftableRecipes = new ArrayList<>();
        for (CookingPotRecipe recipe : recipes) {
            if (entity != null && !canRecipeFitCookingPot(recipe, entity)) {
                continue;
            }
            if (IngredientMatching.containsIngredients(
                    recipe.getIngredients(), available, matches, ItemStack::getAmount)) {
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
        FarmersDelightApi api = FarmersDelightApi.get();
        for (JumpTarget target : api.findRecipesProducing(item)) {
            RecipeType type = api.recipeType(target.typeId());
            if (type != null) {
                return new LinkedAddonRecipe(type, target.recipeId());
            }
        }
        return null;
    }

    // Returns the live stacks rather than copies: every consumer only reads them (containsIngredients copies
    // the amounts into a local array), and this runs for each list draw while the "craftable only" filter is
    // on, so cloning every slot per call was pure allocation. The same instances also key the per-draw
    // matching memo, which is what makes one snapshot per draw the right shape.
    private List<ItemStack> getAvailableCookingPotItems(CookingPotBlockEntity entity, Player player) {
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && !item.getType().isAir()) {
                items.add(item);
            }
        }
        if (entity != null) {
            for (ItemStack item : entity.getInventory()) {
                if (item != null && !item.getType().isAir()) {
                    items.add(item);
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
