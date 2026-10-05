package com.huidu.farmersdelight.gui;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.recipe.SpecialRecipeInfo;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.ItemUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

// Owns every ItemStack/Component builder used by the special-recipe list and detail pages. These are pure
// construction helpers: they read the shared gui config / inventory and produce display items, with no
// navigation or page state of their own. The gui keeps the draw/click flow (which mutates currentPage,
// catalystCycle and selectedSpecialRecipeId) and delegates the builders here.
final class SpecialRecipeRenderer {

    // A slot that carries only hover text is drawn with the transparent "air" item model, so its carrier
    // item stays invisible while still offering the name/lore underneath the cursor.
    private static final NamespacedKey AIR_ITEM_MODEL = NamespacedKey.minecraft("air");

    private final RecipeViewGui gui;
    private final FarmersDelightPlugin plugin;

    SpecialRecipeRenderer(RecipeViewGui gui, FarmersDelightPlugin plugin) {
        this.gui = gui;
        this.plugin = plugin;
    }

    ItemStack createSpecialRecipeListDisplayItem(SpecialRecipeInfo info, Player player) {
        ItemStack icon = ItemUtils.createItem(info.iconItemId());
        if (icon == null || icon.getType().isAir()) {
            icon = new ItemStack(Material.KNOWLEDGE_BOOK);
        }
        ItemMeta meta = icon.getItemMeta();
        meta.displayName(translatable(info.titleKey(), NamedTextColor.GOLD));
        List<Component> lore = new ArrayList<>(translatableKeys(info.descriptionKeys(), NamedTextColor.GRAY));
        if (!isListOnlySpecial(info)) {
            lore.add(Component.text(""));
            lore.add(gui.tr("gui.recipe.click_to_view", NamedTextColor.YELLOW));
        }
        meta.lore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    boolean isListOnlySpecial(SpecialRecipeInfo info) {
        return info != null && SpecialRecipeInfo.DISPLAY_ITEM_DESCRIPTION.equalsIgnoreCase(info.displayType());
    }

    // Shared translatable-component builders: every condition/description label disables italics the same
    // way, so the repeated .color(<c>).decoration(ITALIC,false) chain lives in one place.
    private static Component translatable(String key, NamedTextColor color) {
        return Component.translatable(key).color(color).decoration(TextDecoration.ITALIC, false);
    }

    private static List<Component> translatableLore(String key, NamedTextColor color) {
        return List.of(translatable(key, color));
    }

    private static List<Component> translatableKeys(List<String> keys, NamedTextColor color) {
        List<Component> lore = new ArrayList<>(keys.size());
        for (String key : keys) {
            lore.add(translatable(key, color));
        }
        return lore;
    }

    // Split embedded newlines into separate server-side lore components because the client renders a
    // translated component as one line.
    private static List<Component> translatedLines(List<String> keys, NamedTextColor color, Player player) {
        List<Component> lore = new ArrayList<>();
        for (String key : keys) {
            if (key == null) {
                continue;
            }
            String resolved = I18n.get(key, player);
            String[] lines = resolved.split("\n", -1);
            for (String line : lines) {
                lore.add(Component.text(line).color(color).decoration(TextDecoration.ITALIC, false));
            }
        }
        return lore;
    }

    /** Expands special-recipe slot entries into concrete display items ("#tag" / behavior-list refs). */
    List<ItemStack> expandSpecialEntries(List<SpecialRecipeInfo.SlotEntry> entries) {
        if (entries.isEmpty()) {
            return List.of();
        }
        List<ItemStack> options = new ArrayList<>();
        for (SpecialRecipeInfo.SlotEntry entry : entries) {
            options.addAll(ItemUtils.createSlotItems(entry.itemId(), entry.behaviorBlockId(), entry.behaviorListKey()));
        }
        return options;
    }

    /**
     * A catalyst cycle slot item. Pagination redraws cycle slots every tick, so each candidate carries the
     * full catalyst lore when showFullList is set.
     */
    ItemStack createSpecialCycleDisplay(ItemStack item, int currentIndex, int total,
                                        boolean showFullList, List<ItemStack> options, Player player) {
        ItemStack copy = item.clone();
        ItemMeta meta = copy.getItemMeta();
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        if (showFullList && !options.isEmpty()) {
            for (ItemStack catalyst : options) {
                lore.add(Component.text("- " + ItemUtils.getDisplayName(catalyst, player))
                        .color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false));
            }
        }
        lore.add(Component.text(""));
        lore.add(gui.tr("gui.recipe.auto_cycle", currentIndex + 1, total));
        meta.lore(lore);
        copy.setItemMeta(meta);
        return copy;
    }

    ItemStack createCombinedDescriptionItem(List<String> translationKeys, Player player) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        // Hide the carrier: the description is text shown on hover, so render the slot with the
        // transparent "air" item model instead of a visible paper icon.
        meta.setItemModel(AIR_ITEM_MODEL);
        if (translationKeys.isEmpty()) {
            meta.displayName(Component.text(""));
            meta.lore(List.of());
            item.setItemMeta(meta);
            return item;
        }
        meta.displayName(translatable(translationKeys.get(0), NamedTextColor.WHITE));
        List<Component> lore = translatableKeys(translationKeys.subList(1, translationKeys.size()), NamedTextColor.GRAY);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    ItemStack createSlotEntryItem(SpecialRecipeInfo.SlotEntry entry, Player player, NamedTextColor nameColor) {
        List<ItemStack> options = ItemUtils.createSlotItems(entry.itemId(), entry.behaviorBlockId(), entry.behaviorListKey());
        ItemStack item = options.isEmpty() ? new ItemStack(Material.BARRIER) : options.get(0).clone();
        ItemMeta meta = item.getItemMeta();
        // A "#tag" / behavior-list reference has no single member name; keep each member's own display name.
        boolean referenced = entry.itemId() != null && entry.itemId().startsWith("#") || entry.isBehaviorList();
        // Only override the item's own name/lore when the entry supplies one, so an entry without a name
        // or lore keeps the item's existing display name and tooltip.
        if (!referenced && entry.nameKey() != null && !entry.nameKey().isEmpty()) {
            meta.displayName(translatable(entry.nameKey(), nameColor));
        }
        if (!entry.loreKeys().isEmpty()) {
            meta.lore(translatableKeys(entry.loreKeys(), NamedTextColor.GRAY));
        }
        item.setItemMeta(meta);
        return item;
    }

    void setConditionSlot(RecipeViewGuiConfig.SpecialRecipeDetailConfig detailConfig, String key, int slot,
                          boolean active, boolean titleDrawn, String defaultItemId, String nameKey, String loreKey, NamedTextColor color) {
        if (slot < 0) {
            return;
        }
        if (!active) {
            gui.inventory.setItem(slot, gui.createBackgroundItem(detailConfig));
            return;
        }
        if (titleDrawn) {
            gui.inventory.setItem(slot, createLoreCarrierItem(nameKey, loreKey, color));
            return;
        }
        GuiConfig.GuiItem configured = detailConfig.getItem(key);
        gui.inventory.setItem(slot, configured != null
                ? configured.createItem()
                : createPredefinedConditionItem(defaultItemId, nameKey, loreKey, color));
    }

    // True when the title-layout craftengine section defines an <image> for this condition, meaning the
    // icon's look is drawn by the title and the slot should only carry the hover text.
    boolean hasTitleConditionImage(String guiPath, String key) {
        var guiSection = plugin.getRecipeViewGuiSection();
        var layout = guiSection != null
                ? guiSection.getConfigurationSection(guiPath + ".title-layout.craftengine")
                : null;
        if (layout == null) {
            return false;
        }
        String image = layout.getString(key, "");
        return image != null && !image.isEmpty();
    }

    // The condition icon is drawn by the title (title-layout <image>), so the slot item only carries the
    // hover text. A near-invisible pane with the "air" item model keeps the grid slot clear while still
    // offering the translated name/lore on hover.
    private ItemStack createLoreCarrierItem(String nameKey, String loreKey, NamedTextColor nameColor) {
        ItemStack item = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(AIR_ITEM_MODEL);
        meta.displayName(translatable(nameKey, nameColor));
        meta.lore(translatableLore(loreKey, NamedTextColor.GRAY));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createPredefinedConditionItem(String itemId, String nameKey, String loreKey, NamedTextColor nameColor) {
        ItemStack item = ItemUtils.createItem(itemId);
        if (item == null || item.getType().isAir()) {
            item = new ItemStack(Material.PAPER);
        }
        ItemMeta meta = item.getItemMeta();
        meta.displayName(translatable(nameKey, nameColor));
        meta.lore(translatableLore(loreKey, NamedTextColor.GRAY));
        item.setItemMeta(meta);
        return item;
    }

    /** The detail layout for a special recipe: the full 4-row one when it has extra conditions
     * (sunlight/water/catalyst), otherwise the compact 3-row one. */
    RecipeViewGuiConfig.SpecialRecipeDetailConfig specialDetailFor(SpecialRecipeInfo info) {
        boolean hasConditions = info != null && (info.hasSunlight() || info.hasWater()
                || info.hasCatalystInfo() || !info.catalystSlots().isEmpty());
        return hasConditions ? gui.config.getSpecialRecipeDetail() : gui.config.getSpecialRecipeDetailBasic();
    }
}
