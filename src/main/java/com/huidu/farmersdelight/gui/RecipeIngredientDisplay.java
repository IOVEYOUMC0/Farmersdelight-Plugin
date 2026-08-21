package com.huidu.farmersdelight.gui;

import com.huidu.farmersdelight.i18n.I18n;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Owns every ingredient display builder plus the per-slot auto-cycle animation state that rotates tag /
// choice candidates. The gui owns the CyclicSlot instances driving the tool and catalyst cycles; this class
// keeps the ingredient ones (and their tick/reset) so the gui's tick loop only calls tickIngredientSwitch().
// All shared helpers (tr/colored/itemNameComponent/config) are read via the gui reference.
final class RecipeIngredientDisplay {

    private static final int MAX_COMPACT_INGREDIENT_LINE_LENGTH = 42;
    private static final int PREVIEW_ITEMS = 5;

    private final RecipeViewGui gui;

    private final Map<Integer, List<ItemStack>> animatedIngredientSlots = new HashMap<>();
    private final Map<Integer, RecipeIngredient> animatedIngredientDefinitions = new HashMap<>();
    private final Map<Integer, CyclicSlot> ingredientCycles = new HashMap<>();

    RecipeIngredientDisplay(RecipeViewGui gui) {
        this.gui = gui;
    }

    /** Advances each animated ingredient slot and rewrites its display item. Called from the gui tick. */
    void tickIngredientSwitch() {
        if (animatedIngredientSlots.isEmpty()) {
            return;
        }
        if (gui.player == null || !gui.player.isOnline()) {
            return;
        }

        for (Map.Entry<Integer, List<ItemStack>> entry : animatedIngredientSlots.entrySet()) {
            List<ItemStack> options = entry.getValue();
            if (options.size() <= 1) {
                continue;
            }

            int slot = entry.getKey();
            CyclicSlot cycle = ingredientCycles.computeIfAbsent(slot, s -> new CyclicSlot(gui.INGREDIENT_SWITCH_INTERVAL));
            cycle.tick();
            int nextIndex = cycle.current(options.size());
            RecipeIngredient ingredient = animatedIngredientDefinitions.get(slot);
            if (ingredient == null) {
                continue;
            }
            gui.inventory.setItem(slot, createAnimatedIngredientDisplay(ingredient, options.get(nextIndex), options, gui.player));
        }
    }

    void resetAnimations() {
        animatedIngredientSlots.clear();
        animatedIngredientDefinitions.clear();
        ingredientCycles.clear();
    }

    ItemStack createIngredientDisplay(RecipeIngredient ingredient, Player player, int slot) {
        if (ingredient instanceof RecipeIngredient.Item itemIngredient) {
            List<Component> lore = new ArrayList<>();
            ItemStack display = RecipeIngredientIcons.createItemFromKey(itemIngredient.key());
            lore.add(gui.tr("gui.recipe.ingredient", NamedTextColor.GRAY));
            if (gui.config.isShowIngredientIds()) {
                lore.add(gui.colored("&7" + itemIngredient.key()));
            }
            return createLabeledIngredientDisplay(display, lore, player, gui.itemNameComponent(display, player));
        }

        if (ingredient instanceof RecipeIngredient.Tag tagIngredient) {
            return createAnimatedOrStaticIngredientDisplay(slot, tagIngredient, RecipeIngredientIcons.resolveTagIngredientOptions(tagIngredient), player);
        }

        if (ingredient instanceof RecipeIngredient.Choice choiceIngredient) {
            return createAnimatedOrStaticIngredientDisplay(slot, choiceIngredient, RecipeIngredientIcons.resolveIngredientOptions(choiceIngredient), player);
        }

        return createUnknownIngredientDisplay(player);
    }

    void appendCompactIngredientLore(List<Component> lore, RecipeIngredient ingredient, Player player) {
        List<Component> lines = formatCompactIngredientLoreLines(ingredient, player);
        if (lines.isEmpty()) {
            return;
        }
        lore.add(gui.colored("&8- ").append(lines.getFirst().colorIfAbsent(NamedTextColor.WHITE)));
        for (int i = 1; i < lines.size(); i++) {
            lore.add(gui.colored("&8  ").append(lines.get(i).colorIfAbsent(NamedTextColor.WHITE)));
        }
    }

    void appendCuttingBoardInputLore(List<Component> lore, RecipeIngredient input, Player player) {
        if (input instanceof RecipeIngredient.Item) {
            return; // A single item is already identifiable from its icon.
        }
        appendCompactIngredientLore(lore, input, player);
    }

    void appendMoreIngredientsLine(List<Component> lore, int remainingCount, Player player) {
        if (remainingCount <= 0) {
            return;
        }
        lore.add(gui.tr("gui.recipe.more_ingredients", remainingCount));
        lore.add(gui.tr("gui.recipe.click_to_view_materials", NamedTextColor.YELLOW));
    }

    private ItemStack createAnimatedOrStaticIngredientDisplay(
            int slot,
            RecipeIngredient ingredient,
            List<ItemStack> options,
            Player player
    ) {
        if (!options.isEmpty()) {
            animatedIngredientSlots.put(slot, options);
            ingredientCycles.put(slot, new CyclicSlot(gui.INGREDIENT_SWITCH_INTERVAL));
            animatedIngredientDefinitions.put(slot, ingredient);
            return createAnimatedIngredientDisplay(ingredient, options.getFirst(), options, player);
        }

        return createIngredientPlaceholderDisplay(ingredient, player);
    }

    private ItemStack createAnimatedIngredientDisplay(RecipeIngredient ingredient, ItemStack currentDisplay, List<ItemStack> options, Player player) {
        if (ingredient instanceof RecipeIngredient.Tag tagIngredient) {
            return createTagIngredientDisplay(tagIngredient, currentDisplay, options, player);
        }
        if (ingredient instanceof RecipeIngredient.Choice choiceIngredient) {
            return createChoiceIngredientDisplay(choiceIngredient, currentDisplay, options, player);
        }

        ItemStack display = currentDisplay.clone();
        return createLabeledIngredientDisplay(
                display,
                List.of(gui.tr("gui.recipe.ingredient", NamedTextColor.GRAY)),
                player,
                gui.itemNameComponent(currentDisplay, player)
        );
    }

    private ItemStack createTagIngredientDisplay(RecipeIngredient.Tag tagIngredient, ItemStack currentDisplay, List<ItemStack> options, Player player) {
        ItemStack display = currentDisplay.clone();
        ItemMeta meta = display.getItemMeta();

        List<Component> lore = new ArrayList<>();
        lore.add(gui.tr("gui.recipe.ingredient", NamedTextColor.GRAY));
        lore.add(gui.tr("gui.recipe.matches_line",
                Component.text(options.size()).color(NamedTextColor.YELLOW)));
        appendCyclePosition(lore, currentDisplay, options, player);
        if (gui.config.isShowIngredientIds()) {
            lore.add(gui.tr("gui.recipe.tag_line",
                    Component.text("#" + tagIngredient.key()).color(NamedTextColor.WHITE)));
            appendTagExclusions(lore, tagIngredient, player);
        }

        appendItemPreviewLore(lore, options, 5, player, currentDisplay);

        meta.displayName(gui.itemNameComponent(currentDisplay, player).colorIfAbsent(NamedTextColor.AQUA));
        meta.lore(lore);
        display.setItemMeta(meta);
        return display;
    }

    private ItemStack createChoiceIngredientDisplay(RecipeIngredient.Choice choiceIngredient, ItemStack currentDisplay, List<ItemStack> options, Player player) {
        ItemStack display = currentDisplay.clone();
        ItemMeta meta = display.getItemMeta();

        List<Component> lore = new ArrayList<>();
        lore.add(gui.tr("gui.recipe.ingredient", NamedTextColor.GRAY));
        lore.add(gui.tr("gui.recipe.any_of_line",
                Component.text(choiceIngredient.options().size()).color(NamedTextColor.YELLOW)));
        lore.add(gui.tr("gui.recipe.matches_line",
                Component.text(options.size()).color(NamedTextColor.YELLOW)));
        appendCyclePosition(lore, currentDisplay, options, player);
        appendIngredientPreviewLore(lore, choiceIngredient.options(), choiceIngredient.options().size(), player, currentDisplay);

        meta.displayName(gui.itemNameComponent(currentDisplay, player).colorIfAbsent(NamedTextColor.AQUA));
        meta.lore(lore);
        display.setItemMeta(meta);
        return display;
    }

    private void appendTagExclusions(List<Component> lore, RecipeIngredient.Tag tagIngredient, Player player) {
        if (!gui.config.isShowIngredientIds()) {
            return;
        }
        for (Key excludedItem : tagIngredient.excludedItems()) {
            lore.add(gui.colored("&c- ").append(gui.itemNameComponent(RecipeIngredientIcons.createItemFromKey(excludedItem), player).colorIfAbsent(NamedTextColor.RED)));
        }
        for (Key excludedTag : tagIngredient.excludedTags()) {
            lore.add(gui.colored("&c- #" + excludedTag));
        }
    }

    private ItemStack createIngredientPlaceholderDisplay(RecipeIngredient ingredient, Player player) {
        return createLabeledIngredientDisplay(
                new ItemStack(Material.NAME_TAG),
                formatIngredientLoreLines(ingredient, player),
                player,
                gui.tr("gui.recipe.ingredient", NamedTextColor.AQUA)
        );
    }

    private ItemStack createUnknownIngredientDisplay(Player player) {
        return createLabeledIngredientDisplay(
                new ItemStack(Material.BARRIER),
                List.of(gui.tr("gui.recipe.unknown", NamedTextColor.WHITE)),
                player,
                gui.tr("gui.recipe.ingredient", NamedTextColor.AQUA)
        );
    }

    private ItemStack createLabeledIngredientDisplay(ItemStack baseDisplay, List<Component> lore, Player player, Component displayName) {
        ItemStack display = baseDisplay.clone();
        ItemMeta meta = display.getItemMeta();
        meta.displayName(displayName.colorIfAbsent(NamedTextColor.AQUA));
        meta.lore(lore);
        display.setItemMeta(meta);
        return display;
    }

    private List<Component> formatCompactIngredientLoreLines(RecipeIngredient ingredient, Player player) {
        if (ingredient instanceof RecipeIngredient.Item itemIngredient) {
            return List.of(gui.itemNameComponent(RecipeIngredientIcons.createItemFromKey(itemIngredient.key()), player));
        }
        if (ingredient instanceof RecipeIngredient.Choice choiceIngredient) {
            List<ItemStack> options = RecipeIngredientIcons.resolveIngredientOptions(choiceIngredient);
            if (options.isEmpty()) {
                return List.of(gui.tr("gui.recipe.no_matching_items", NamedTextColor.GRAY));
            }
            return formatCompactItemOptions(options, player);
        }
        if (ingredient instanceof RecipeIngredient.Tag tagIngredient) {
            // Clone only the first 6 for showing names; read the full count from the cache.
            int totalSize = RecipeIngredientIcons.resolveTagIngredientOptionsSize(tagIngredient);
            if (totalSize == 0) {
                return List.of(gui.tr("gui.recipe.no_matching_items", NamedTextColor.GRAY));
            }
            List<ItemStack> previewOptions = RecipeIngredientIcons.resolveTagIngredientOptionsPreview(tagIngredient, 6);
            int previewCount = previewOptions.size();
            List<Component> lines = formatCompactItemOptions(previewOptions, player);
            if (totalSize > previewCount) {
                lines.add(gui.tr("gui.recipe.more_items",
                        totalSize - previewCount));
            }
            return lines;
        }
        return List.of(gui.tr("gui.recipe.unknown", NamedTextColor.WHITE));
    }

    private List<Component> formatCompactItemOptions(List<ItemStack> options, Player player) {
        List<Component> lines = new ArrayList<>();
        Component current = Component.empty();
        int currentLength = 0;
        boolean hasCurrent = false;

        for (ItemStack option : options) {
            Component name = gui.itemNameComponent(option, player).colorIfAbsent(NamedTextColor.WHITE);
            int nameLength = Math.max(1, getItemDisplayName(option, player).length());
            int extraLength = hasCurrent ? nameLength + 2 : nameLength;
            if (hasCurrent && currentLength + extraLength > MAX_COMPACT_INGREDIENT_LINE_LENGTH) {
                lines.add(current);
                current = Component.empty();
                currentLength = 0;
                hasCurrent = false;
            }
            if (hasCurrent) {
                current = current.append(Component.text(", ", NamedTextColor.GRAY));
                currentLength += 2;
            }
            current = current.append(name);
            currentLength += nameLength;
            hasCurrent = true;
        }

        if (hasCurrent) {
            lines.add(current);
        }
        return lines;
    }

    List<Component> formatIngredientLoreLines(RecipeIngredient ingredient, Player player) {
        List<Component> lines = new ArrayList<>();
        if (ingredient instanceof RecipeIngredient.Item itemIngredient) {
            lines.add(gui.itemNameComponent(RecipeIngredientIcons.createItemFromKey(itemIngredient.key()), player).colorIfAbsent(NamedTextColor.WHITE));
            return lines;
        }
        if (ingredient instanceof RecipeIngredient.Tag tagIngredient) {
            List<ItemStack> options = RecipeIngredientIcons.resolveTagIngredientOptions(tagIngredient);
            if (options.isEmpty()) {
                lines.add(gui.tr("gui.recipe.no_matching_items", NamedTextColor.GRAY));
                if (gui.config.isShowIngredientIds()) {
                    lines.add(gui.colored("&8#" + tagIngredient.key()));
                }
                return lines;
            }
            appendItemPreviewLore(lines, options, player);
            if (gui.config.isShowIngredientIds()) {
                lines.add(gui.colored("&8#" + tagIngredient.key()));
                appendTagExclusions(lines, tagIngredient, player);
            }
            return lines;
        }
        if (ingredient instanceof RecipeIngredient.Choice choiceIngredient) {
            List<ItemStack> options = RecipeIngredientIcons.resolveIngredientOptions(choiceIngredient);
            if (options.isEmpty()) {
                lines.add(gui.tr("gui.recipe.no_matching_items", NamedTextColor.GRAY));
                return lines;
            }
            appendItemPreviewLore(lines, options, player);
            return lines;
        }
        lines.add(gui.tr("gui.recipe.unknown", NamedTextColor.WHITE));
        return lines;
    }

    private void appendItemPreviewLore(List<Component> lore, List<ItemStack> options, Player player) {
        appendItemPreviewLore(lore, options, 5, player, null);
    }

    void appendItemPreviewLore(
            List<Component> lore,
            List<ItemStack> options,
            int previewLimit,
            Player player,
            ItemStack currentDisplay
    ) {
        List<ItemStack> previewOptions = filterCurrentPreviewOption(options, currentDisplay);
        if (previewOptions.isEmpty()) {
            return;
        }

        int displayed = Math.min(previewOptions.size(), previewLimit);
        for (int i = 0; i < displayed; i++) {
            lore.add(gui.colored("&8- ").append(gui.itemNameComponent(previewOptions.get(i), player).colorIfAbsent(NamedTextColor.WHITE)));
        }
        appendMoreItemsLine(lore, previewOptions.size() - displayed, player);
    }

    private List<ItemStack> filterCurrentPreviewOption(List<ItemStack> options, ItemStack currentDisplay) {
        if (currentDisplay == null) {
            return options;
        }
        String currentKey = RecipeIngredientIcons.buildIngredientDisplayKey(currentDisplay);
        List<ItemStack> filtered = new ArrayList<>();
        for (ItemStack option : options) {
            if (!RecipeIngredientIcons.buildIngredientDisplayKey(option).equals(currentKey)) {
                filtered.add(option);
            }
        }
        return filtered;
    }

    private void appendIngredientPreviewLore(
            List<Component> lore,
            List<RecipeIngredient> options,
            int previewLimit,
            Player player,
            ItemStack currentDisplay
    ) {
        LinkedHashMap<String, ItemStack> displayOptions = new LinkedHashMap<>();
        for (RecipeIngredient option : options) {
            for (ItemStack display : RecipeIngredientIcons.resolveIngredientOptions(option)) {
                if (!gui.isDisplayableItem(display)) {
                    continue;
                }
                displayOptions.putIfAbsent(RecipeIngredientIcons.buildIngredientDisplayKey(display), display);
            }
        }

        if (displayOptions.isEmpty()) {
            lore.add(gui.tr("gui.recipe.no_matching_items", NamedTextColor.GRAY));
            return;
        }

        appendItemPreviewLore(lore, new ArrayList<>(displayOptions.values()), previewLimit, player, currentDisplay);
    }

    private void appendCyclePosition(List<Component> lore, ItemStack currentDisplay, List<ItemStack> options, Player player) {
        if (options.size() <= 1) {
            return;
        }
        String currentKey = RecipeIngredientIcons.buildIngredientDisplayKey(currentDisplay);
        int currentIndex = 0;
        for (int i = 0; i < options.size(); i++) {
            if (RecipeIngredientIcons.buildIngredientDisplayKey(options.get(i)).equals(currentKey)) {
                currentIndex = i + 1;
                break;
            }
        }
        lore.add(gui.tr("gui.recipe.auto_cycle",
                Math.max(1, currentIndex), options.size()));
    }

    private void appendMoreItemsLine(List<Component> lore, int remainingCount, Player player) {
        if (remainingCount <= 0) {
            return;
        }
        lore.add(gui.tr("gui.recipe.more_items", remainingCount));
    }

    private String unknownRecipeText(Player player) {
        return I18n.get("gui.recipe.unknown", player);
    }

    private String getItemDisplayName(ItemStack item, Player player) {
        if (item == null) {
            return unknownRecipeText(player);
        }
        return ItemUtils.getDisplayName(item, player);
    }
}