package com.huidu.farmersdelight.api.gui;

import com.huidu.farmersdelight.gui.GuiConfig;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

/**
 * Builds a GUI decoration item from the items: section shape FarmersDelight's own GUIs use.
 */
public final class GuiItems {

    private GuiItems() {
    }

    /** Builds the item, or null when the section is null. */
    public static ItemStack build(ConfigurationSection section) {
        return section == null
                ? null
                : GuiConfig.GuiItem.fromConfig(section).createItem();
    }

    /**
     * Builds the item with {placeholder} substitutions applied to its name and lore, for the
     * decorations whose text carries runtime values (page numbers, counts).
     */
    public static ItemStack build(ConfigurationSection section, Map<String, String> placeholders) {
        return section == null
                ? null
                : GuiConfig.GuiItem.fromConfig(section).createItem(placeholders);
    }
}
