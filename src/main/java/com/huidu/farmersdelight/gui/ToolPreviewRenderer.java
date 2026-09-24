package com.huidu.farmersdelight.gui;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.recipe.CuttingBoardRecipe;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.ItemUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// Renders + resolves the cutting-board tool preview: caches which items satisfy a tool requirement and
// turns a requirement into the rotating display item used by the detail slot and the list lore. Kept as a
// pure renderer (no animation state) -- the auto-cycle indices and CyclicSlot stay on the GUI, which tells
// this class which preview frame to show via currentToolPreviewIndex.
final class ToolPreviewRenderer {

    // Tool preview options include item/tag identity and exclusions, so cache by the complete requirement.
    private static final Map<CuttingBoardRecipe.ToolRequirement, List<ItemStack>> toolPreviewCache = new ConcurrentHashMap<>();
    private final RecipeViewGui gui;
    private final FarmersDelightPlugin plugin;

    ToolPreviewRenderer(RecipeViewGui gui, FarmersDelightPlugin plugin) {
        this.gui = gui;
        this.plugin = plugin;
    }

    static void clearToolPreviewCache() {
        toolPreviewCache.clear();
    }

    ItemStack createToolDisplayItem(CuttingBoardRecipe.ToolRequirement tool, int totalTools,
                                    int currentIndex, Player player) {
        List<ItemStack> previewOptions = resolveToolPreviewOptions(tool);
        int safePreviewIndex = 0;
        if (!previewOptions.isEmpty()) {
            safePreviewIndex = gui.currentToolPreviewIndex % previewOptions.size();
        }
        ItemStack toolItem = null;
        if (!previewOptions.isEmpty()) {
            toolItem = previewOptions.get(safePreviewIndex).clone();
        }
        if (toolItem == null || toolItem.getType() == Material.BARRIER) {
            toolItem = new ItemStack(Material.IRON_AXE);
        }

        ItemMeta toolMeta = toolItem.getItemMeta();
        toolMeta.displayName(gui.itemNameComponent(toolItem, player).colorIfAbsent(NamedTextColor.WHITE));

        // Same layout as animated ingredients: the requirement label stays fixed while the icon rotates, so
        // a tag-based tool need (e.g. #minecraft:axes) never reads as a single specific tool.
        List<Component> lore = new ArrayList<>();
        lore.add(gui.tr("gui.recipe.tool", NamedTextColor.GRAY));
        if (!previewOptions.isEmpty()) {
            lore.add(gui.tr("gui.recipe.matches_line",
                    Component.text(previewOptions.size()).color(NamedTextColor.YELLOW)));
        }
        if (totalTools > 1 || previewOptions.size() > 1) {
            lore.add(gui.tr("gui.recipe.auto_cycle",
                    currentIndex + 1, totalTools > 1 ? totalTools : previewOptions.size()));
        }
        if (gui.config.isShowIngredientIds()) {
            lore.add(gui.tr("gui.recipe.tag_line",
                    Component.text("#" + tool.key()).color(NamedTextColor.WHITE)));
        }
        if (previewOptions.size() > 1) {
            gui.ingredientDisplay.appendItemPreviewLore(lore, previewOptions, 5, player, toolItem);
        }
        toolMeta.lore(lore);
        toolItem.setItemMeta(toolMeta);

        return toolItem;
    }

    ItemStack createToolPreviewItem(CuttingBoardRecipe.ToolRequirement tool) {
        List<ItemStack> previewOptions = resolveToolPreviewOptions(tool);
        if (!previewOptions.isEmpty()) {
            return previewOptions.getFirst().clone();
        }

        return new ItemStack(Material.IRON_AXE);
    }

    // How many preview items a tool cycles through. The cycle timer only needs the count, and the
    // cloning resolve above allocates a fresh list plus a copy of every item on each call.
    int resolveToolPreviewOptionsSize(CuttingBoardRecipe.ToolRequirement tool) {
        return toolPreviewCache.computeIfAbsent(tool, this::computeToolPreviewOptions).size();
    }

    List<ItemStack> resolveToolPreviewOptions(CuttingBoardRecipe.ToolRequirement tool) {
        List<ItemStack> cached = toolPreviewCache.computeIfAbsent(tool, this::computeToolPreviewOptions);
        List<ItemStack> copy = new ArrayList<>(cached.size());
        for (ItemStack item : cached) {
            copy.add(item.clone());
        }
        return copy;
    }

    private List<ItemStack> computeToolPreviewOptions(CuttingBoardRecipe.ToolRequirement tool) {
        if (tool.tag() && Constants.TAG_KNIVES.equals(tool.key().toString())) {
            return finalizeToolPreviewOptions(createKnifePreviewItems(), tool);
        }

        List<ItemStack> previewOptions = finalizeToolPreviewOptions(
                RecipeIngredientIcons.resolveIngredientOptions(tool.asIngredient()), tool);
        if (!previewOptions.isEmpty()) {
            return previewOptions;
        }

        // Action keys are predicates rather than item/tag IDs, so map them to their visible tool families.
        previewOptions = new ArrayList<>();
        switch (tool.key().toString()) {
            case Constants.TAG_KNIVES -> previewOptions.addAll(createKnifePreviewItems());
            case "farmersdelight:axe_dig", "farmersdelight:axe_strip", "minecraft:axes" -> previewOptions.addAll(createVanillaToolPreviewItems("_axe"));
            case "farmersdelight:pickaxe_dig", "minecraft:pickaxes" -> previewOptions.addAll(createVanillaToolPreviewItems("_pickaxe"));
            case "farmersdelight:shovel_dig", "minecraft:shovels" -> previewOptions.addAll(createVanillaToolPreviewItems("_shovel"));
            case "minecraft:shears" -> previewOptions.add(new ItemStack(Material.SHEARS));
            default -> { }
        }
        return finalizeToolPreviewOptions(previewOptions, tool);
    }

    private List<ItemStack> createKnifePreviewItems() {
        List<ItemStack> knives = new ArrayList<>();
        for (String knifeId : plugin.getConfigStringList("knife-items.items")) {
            ItemStack knife = RecipeIngredientIcons.createItemFromKey(Key.of(knifeId));
            if (gui.isDisplayableItem(knife)) {
                knives.add(knife);
            }
        }
        if (knives.isEmpty()) {
            knives.add(new ItemStack(Material.IRON_SWORD));
        }
        return knives;
    }

    private List<ItemStack> createVanillaToolPreviewItems(String suffix) {
        List<ItemStack> items = new ArrayList<>();
        for (Material material : Material.values()) {
            // Skipping legacy materials avoids forcing CraftLegacy into its one-time class init
            // (resolving a legacy item type routes through CraftLegacy.fromLegacy).
            if (material.isLegacy() || !material.isItem()) continue;
            String name = material.name();
            if (name.endsWith(suffix.toUpperCase(Locale.ROOT))) {
                items.add(new ItemStack(material));
            }
        }
        if (items.isEmpty()) {
            // Last resort: show at least one iron tool.
            items.add(switch (suffix) {
                case "_axe" -> new ItemStack(Material.IRON_AXE);
                case "_pickaxe" -> new ItemStack(Material.IRON_PICKAXE);
                case "_shovel" -> new ItemStack(Material.IRON_SHOVEL);
                default -> new ItemStack(Material.IRON_AXE);
            });
        }
        return items;
    }

    private List<ItemStack> finalizeToolPreviewOptions(
            List<ItemStack> candidates,
            CuttingBoardRecipe.ToolRequirement tool
    ) {
        Map<String, ItemStack> unique = new LinkedHashMap<>();
        for (ItemStack item : candidates) {
            if (gui.isDisplayableItem(item) && !isExcludedToolPreview(item, tool)) {
                unique.putIfAbsent(RecipeIngredientIcons.buildIngredientDisplayKey(item), item);
            }
        }
        return RecipeIngredientIcons.sortIngredientDisplayItems(unique.values());
    }

    private boolean isExcludedToolPreview(ItemStack item, CuttingBoardRecipe.ToolRequirement tool) {
        if (tool.excludedItems().stream().anyMatch(excluded -> ItemUtils.matchesItemId(item, excluded))) {
            return true;
        }
        Set<String> customTags = ItemUtils.getItemTagIds(item);
        for (Key excludedTag : tool.excludedTags()) {
            if (customTags.contains(excludedTag.toString())
                    || ItemUtils.matchesVanillaItemTag(item, excludedTag, Set.of(), Set.of())) {
                return true;
            }
        }
        return false;
    }

}
