package com.huidu.farmersdelight.gui;

import com.huidu.farmersdelight.recipe.CookingPotRecipe;
import com.huidu.farmersdelight.recipe.CuttingBoardRecipe;
import com.huidu.farmersdelight.recipe.RecipeIngredient;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the ItemStack shown for one recipe in the list pages. The list draw and the page fill only need the
 * finished item, so the lore assembly lives here and the GUI keeps the page state and navigation.
 */
final class RecipeListRenderer {

    private final RecipeViewGui gui;

    RecipeListRenderer(RecipeViewGui gui) {
        this.gui = gui;
    }

    ItemStack createCookingPotRecipeDisplayItem(CookingPotRecipe recipe, Player player) {
        ItemStack result = recipe.getResult().clone();
        ItemMeta meta = result.getItemMeta();

        List<Component> lore = new ArrayList<>();
        // Keep the result item's own description instead of replacing it, matching the addon recipe books.
        List<Component> originalLore = meta.lore();
        if (originalLore != null && !originalLore.isEmpty()) {
            lore.addAll(originalLore);
            lore.add(Component.text(""));
        }
        lore.add(gui.tr("gui.recipe.ingredients_label", NamedTextColor.GRAY));
        List<RecipeIngredient> ingredients = recipe.getIngredients();
        int displayedIngredients = Math.min(ingredients.size(), gui.config.getRecipeListMaxPreviewIngredients());
        for (int i = 0; i < displayedIngredients; i++) {
            gui.ingredientDisplay.appendCompactIngredientLore(lore, ingredients.get(i), player);
        }
        gui.ingredientDisplay.appendMoreIngredientsLine(lore, ingredients.size() - displayedIngredients, player);
        if (recipe.needsContainer() && recipe.getContainer() != null) {
            lore.add(gui.tr("gui.recipe.container_line",
                    gui.itemNameComponent(recipe.getContainer(), player).colorIfAbsent(NamedTextColor.AQUA)));
        }
        if (recipe.getExperience() > 0.0D || recipe.getCookTime() > 0) {
            // Use the shared cookTimeSeconds() (ceil, min 1s) so the list preview matches the detail screen;
            // raw integer /20 shows a misleading "0s" for sub-20-tick recipes.
            String cookTimeStr = gui.cookTimeSeconds(recipe) + gui.i18nOrDefault(player);
            lore.add(gui.tr("gui.recipe.cook_time_line",
                    Component.text(cookTimeStr).color(NamedTextColor.AQUA)));
        }
        lore.add(Component.text(""));
        lore.add(gui.tr("gui.recipe.click_to_view", NamedTextColor.YELLOW));

        meta.lore(lore);
        result.setItemMeta(meta);
        return result;
    }

    ItemStack createCuttingBoardRecipeDisplayItem(CuttingBoardRecipe recipe, Player player) {
        ItemStack input = recipe.getInputDisplay().clone();
        ItemMeta meta = input.getItemMeta();

        List<Component> lore = new ArrayList<>();
        // Keep the input item's own description instead of replacing it, matching the addon recipe books.
        List<Component> originalLore = meta.lore();
        if (originalLore != null && !originalLore.isEmpty()) {
            lore.addAll(originalLore);
            lore.add(Component.text(""));
        }
        lore.add(gui.tr("gui.recipe.tool_line",
                formatToolListComponent(recipe.getTools(), player).colorIfAbsent(NamedTextColor.YELLOW)));
        // Show the input type so players can see when multiple alternatives exist without opening details.
        gui.ingredientDisplay.appendCuttingBoardInputLore(lore, recipe.getInput(), player);
        lore.add(gui.tr("gui.recipe.results_label", NamedTextColor.GRAY));
        int maxPreview = gui.config.getRecipeListMaxPreviewIngredients();
        List<CuttingBoardRecipe.ResultEntry> results = recipe.getResults();
        int displayed = Math.min(results.size(), maxPreview);
        for (int i = 0; i < displayed; i++) {
            CuttingBoardRecipe.ResultEntry result = results.get(i);
            Component line = gui.itemNameComponent(result.item(), player).colorIfAbsent(NamedTextColor.WHITE);
            if (result.chance() < 1.0d) {
                line = line.append(Component.text(" (" + (int) Math.round(result.chance() * 100) + "%)", NamedTextColor.GRAY));
            }
            lore.add(gui.colored("&8- ").append(line));
            if (gui.config.isShowIngredientIds()) {
                lore.add(gui.colored("&7  " + RecipeIngredientIcons.buildIngredientDisplayKey(result.item())));
            }
        }
        int remaining = results.size() - displayed;
        if (remaining > 0) {
            lore.add(gui.tr("gui.recipe.more_items", remaining));
        }
        lore.add(Component.text(""));
        lore.add(gui.tr("gui.recipe.click_to_view", NamedTextColor.YELLOW));

        meta.lore(lore);
        input.setItemMeta(meta);
        return input;
    }

    private Component formatToolListComponent(List<CuttingBoardRecipe.ToolRequirement> tools, Player player) {
        Component result = Component.empty();
        for (int i = 0; i < tools.size(); i++) {
            if (i > 0) {
                result = result.append(Component.text(", ", NamedTextColor.GRAY));
            }
            result = result.append(gui.itemNameComponent(
                    gui.toolPreviewRenderer.createToolPreviewItem(tools.get(i)), player));
        }
        return result;
    }
}
