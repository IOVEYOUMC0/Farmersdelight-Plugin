package com.huidu.farmersdelight.gui;

import com.huidu.farmersdelight.recipe.CookingPotRecipe;
import com.huidu.farmersdelight.recipe.CuttingBoardRecipe;
import com.huidu.farmersdelight.recipe.RecipeIngredient;
import com.huidu.farmersdelight.util.ItemUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Draws the recipe-detail view (cooking pot and cutting board) into an open RecipeViewGui: result,
 * ingredient, container, tool, and result slots plus the cooking process bar and its animated frames.
 * A companion of RecipeViewGui holding a back-reference; it reads GUI state and shared item/text helpers
 * through that owner. Extracted from RecipeViewGui to keep the detail-drawing concern in one focused class.
 */
final class RecipeDetailRenderer {

    private static final int COOKING_PROCESS_BAR_FRAMES = 20;
    // These N+1 distinct progress bar frame items are identical per frame; cache them (cleared on reload) so the
    // GUI tick animation does not recreate a CraftEngine item every 4 ticks.
    private static final ItemStack[] processBarFrameCache = new ItemStack[COOKING_PROCESS_BAR_FRAMES + 1];

    private final RecipeViewGui gui;

    RecipeDetailRenderer(RecipeViewGui gui) {
        this.gui = gui;
    }

    static void clearProcessBarFrameCache() {
        java.util.Arrays.fill(processBarFrameCache, null);
    }

    void drawCookingPotDetail(CookingPotRecipe recipe, RecipeViewGuiConfig.RecipeDetailConfig detailConfig, Player player) {
        if (detailConfig.getResultSlot() >= 0) {
            gui.inventory.setItem(detailConfig.getResultSlot(), recipe.getResult().clone());
        }

        fillIngredientSlots(detailConfig, detailConfig.getIngredientSlots(), recipe.getIngredients(), player);

        if (recipe.needsContainer() && recipe.getContainer() != null && detailConfig.getContainerSlot() >= 0) {
            ItemStack containerItem = recipe.getContainer().clone();
            ItemMeta containerMeta = containerItem.getItemMeta();
            containerMeta.displayName(gui.itemNameComponent(recipe.getContainer(), player).colorIfAbsent(NamedTextColor.AQUA));
            containerMeta.lore(List.of(gui.tr("gui.recipe.container", NamedTextColor.GRAY)));
            containerItem.setItemMeta(containerMeta);
            gui.inventory.setItem(detailConfig.getContainerSlot(), containerItem);
        }

        setCookingPotProcessItems(recipe, detailConfig, player);
    }

    private void setCookingPotProcessItems(CookingPotRecipe recipe,
                                           RecipeViewGuiConfig.RecipeDetailConfig detailConfig,
                                           Player player) {
        int arrowSlot = detailConfig.getArrowSlot();
        if (arrowSlot < 0 || arrowSlot >= gui.inventory.getSize()) {
            return;
        }

        GuiConfig.GuiItem configured = detailConfig.getItem("arrow");
        Map<String, String> placeholders = cookingInfoPlaceholders(recipe, player);
        ItemStack processItem = configured == null ? new ItemStack(Material.CLOCK) : configured.createItem(placeholders);
        ItemMeta meta = processItem.getItemMeta();
        if (meta != null) {
            if (configured == null) {
                meta.displayName(gui.tr("gui.recipe.cook_time", NamedTextColor.YELLOW));
            }
            if (meta.lore() == null || meta.lore().isEmpty()) {
                meta.lore(List.of(
                        gui.tr("gui.recipe.cook_time_line", NamedTextColor.GRAY,
                                Component.text(placeholders.get("cook_time")).color(NamedTextColor.AQUA)),
                        gui.tr("gui.recipe.experience_line", NamedTextColor.GRAY,
                                Component.text(placeholders.get("experience")).color(NamedTextColor.GREEN))
                ));
            }
            processItem.setItemMeta(meta);
        }
        gui.inventory.setItem(arrowSlot, processItem);
        setCookingPotProcessBar(recipe, detailConfig);
    }

    private void setCookingPotProcessBar(CookingPotRecipe recipe,
                                         RecipeViewGuiConfig.RecipeDetailConfig detailConfig) {
        int progressSlot = getCookingPotProcessBarSlot(detailConfig);
        if (progressSlot < 0) {
            return;
        }

        gui.inventory.setItem(progressSlot, createCookingPotProcessBarItem(cookingProcessBarFrame(recipe)));
    }

    int getCookingPotProcessBarSlot(RecipeViewGuiConfig.RecipeDetailConfig detailConfig) {
        // Prefer the progress slot cached at parse time, avoiding a layout rescan each GUI tick.
        int configuredProgressSlot = detailConfig.getProgressSlot();
        if (configuredProgressSlot >= 0 && configuredProgressSlot < gui.inventory.getSize()) {
            return configuredProgressSlot;
        }

        // Fallback: when an arrow slot is configured but no explicit progress slot, place the bar one slot
        // below the arrow (only occupying that slot if it is empty/background/decoration, to avoid
        // overwriting functional slots).
        int arrowSlot = detailConfig.getArrowSlot();
        int fallbackSlot = arrowSlot + 9;
        if (arrowSlot < 0 || fallbackSlot < 0 || fallbackSlot >= gui.inventory.getSize()) {
            return -1;
        }
        String slotType = detailConfig.getSlotType(fallbackSlot);
        if (slotType != null && !"background".equals(slotType) && !"decoration".equals(slotType)) {
            return -1;
        }
        return fallbackSlot;
    }

    static ItemStack createCookingPotProcessBarItem(int frame) {
        int safeFrame = Math.max(0, Math.min(COOKING_PROCESS_BAR_FRAMES, frame));
        ItemStack cached = processBarFrameCache[safeFrame];
        if (cached != null) {
            return cached.clone();
        }

        ItemStack item = ItemUtils.createItem("farmersdelight:" + safeFrame);
        if (item == null) {
            item = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        }

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(" "));
            meta.lore(List.of());
            item.setItemMeta(meta);
        }
        processBarFrameCache[safeFrame] = item.clone();
        return item;
    }

    int cookingProcessBarFrame(CookingPotRecipe recipe) {
        int duration = recipe == null ? 0 : recipe.getCookTime();
        if (duration <= 0) {
            return 0;
        }
        int percent = Math.min(100, gui.cookingProcessBarTicks * 100 / duration);
        return Math.max(0, Math.min(COOKING_PROCESS_BAR_FRAMES, percent / 5));
    }

    private String formatCookTime(CookingPotRecipe recipe, Player player) {
        return gui.cookTimeSeconds(recipe) + gui.i18nOrDefault("gui.recipe.seconds_suffix", player, "s");
    }

    private Map<String, String> cookingInfoPlaceholders(CookingPotRecipe recipe, Player player) {
        String cookTime = formatCookTime(recipe, player);
        String experience = formatExperience(recipe == null ? 0.0D : recipe.getExperience());
        return Map.of(
                "cook_time", cookTime,
                "cooking_time", cookTime,
                "time", cookTime,
                "experience", experience,
                "exp", experience
        );
    }

    private String formatExperience(double experience) {
        if (experience <= 0.0D) {
            return "0";
        }
        if (Math.rint(experience) == experience) {
            return String.valueOf((int) experience);
        }
        return String.format(java.util.Locale.ROOT, "%.1f", experience);
    }

    void drawCuttingBoardDetail(CuttingBoardRecipe recipe, RecipeViewGuiConfig.RecipeDetailConfig detailConfig, Player player) {
        if (detailConfig.getInputSlot() >= 0) {
            RecipeIngredient input = recipe.getInput();
            if (input instanceof RecipeIngredient.Tag || input instanceof RecipeIngredient.Choice) {
                // 多选项输入（标签/或选）需注册动画槽位以支持轮播显示，否则只会显示第一个匹配物品
                ItemStack inputDisplay = gui.createIngredientDisplay(input, player, detailConfig.getInputSlot());
                gui.inventory.setItem(detailConfig.getInputSlot(), inputDisplay);
            } else {
                ItemStack inputItem = recipe.getInputDisplay().clone();
                ItemMeta inputMeta = inputItem.getItemMeta();
                inputMeta.displayName(gui.itemNameComponent(inputItem, player).colorIfAbsent(NamedTextColor.RED));
                inputMeta.lore(formatIngredientDetailLoreLines(input, player,
                        gui.tr("gui.recipe.input", NamedTextColor.GRAY)));
                inputItem.setItemMeta(inputMeta);
                gui.inventory.setItem(detailConfig.getInputSlot(), inputItem);
            }
        }

        if (detailConfig.getToolSlot() >= 0) {
            List<CuttingBoardRecipe.ToolRequirement> tools = recipe.getTools();
            if (tools != null && !tools.isEmpty()) {
                int safeIndex = gui.currentToolIndex % tools.size();
                Key currentTool = tools.get(safeIndex).key();
                ItemStack toolItem = gui.createToolDisplayItem(currentTool, tools.size(), safeIndex, player);
                gui.inventory.setItem(detailConfig.getToolSlot(), toolItem);
            }
        }

        fillResultSlots(detailConfig, detailConfig.getResultSlots(), recipe.getResults(), player);
    }

    private void fillIngredientSlots(RecipeViewGuiConfig.BaseConfig guiConfig, List<Integer> slots,
                                     List<RecipeIngredient> ingredients, Player player) {
        for (int i = 0; i < slots.size(); i++) {
            if (i < ingredients.size()) {
                ItemStack ingredientDisplay = gui.createIngredientDisplay(ingredients.get(i), player, slots.get(i));
                gui.inventory.setItem(slots.get(i), ingredientDisplay);
            } else {
                gui.inventory.setItem(slots.get(i), gui.createBackgroundItem(guiConfig));
            }
        }
    }

    private void fillResultSlots(RecipeViewGuiConfig.BaseConfig guiConfig, List<Integer> slots,
                                 List<CuttingBoardRecipe.ResultEntry> results, Player player) {
        for (int i = 0; i < slots.size(); i++) {
            if (i < results.size()) {
                CuttingBoardRecipe.ResultEntry resultEntry = results.get(i);
                ItemStack resultDisplay = resultEntry.item().clone();
                ItemMeta resultMeta = resultDisplay.getItemMeta();
                List<Component> lore = new ArrayList<>();
                if (resultMeta.hasLore()) {
                    lore = new ArrayList<>(resultMeta.lore());
                }
                lore.add(0, gui.tr("gui.recipe.result", NamedTextColor.GREEN));
                if (resultEntry.chance() < 1.0d) {
                    lore.add(1, gui.tr("gui.recipe.chance_line", NamedTextColor.GRAY,
                            Component.text((int) Math.round(resultEntry.chance() * 100))
                                    .color(NamedTextColor.YELLOW)));
                }
                resultMeta.lore(lore);
                resultDisplay.setItemMeta(resultMeta);
                gui.inventory.setItem(slots.get(i), resultDisplay);
            } else {
                gui.inventory.setItem(slots.get(i), gui.createBackgroundItem(guiConfig));
            }
        }
    }

    private List<Component> formatIngredientDetailLoreLines(RecipeIngredient ingredient, Player player, Component category) {
        List<Component> lines = new ArrayList<>();
        lines.add(category);
        if (ingredient instanceof RecipeIngredient.Item itemIngredient) {
            if (gui.config.isShowIngredientIds()) {
                lines.add(gui.colored("&7" + itemIngredient.key()));
            }
            return lines;
        }
        lines.addAll(gui.formatIngredientLoreLines(ingredient, player));
        return removeAdjacentDuplicateComponents(lines);
    }

    private List<Component> removeAdjacentDuplicateComponents(List<Component> lines) {
        if (lines.size() < 2) {
            return lines;
        }
        List<Component> result = new ArrayList<>(lines.size());
        String previous = null;
        for (Component line : lines) {
            String serialized = RecipeViewGui.LEGACY.serialize(line);
            if (!serialized.equals(previous)) {
                result.add(line);
            }
            previous = serialized;
        }
        return result;
    }
}
