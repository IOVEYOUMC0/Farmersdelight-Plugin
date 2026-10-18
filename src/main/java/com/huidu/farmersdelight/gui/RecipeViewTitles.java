package com.huidu.farmersdelight.gui;

import com.huidu.farmersdelight.api.recipe.SpecialRecipeInfo;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Map;

/**
 * Builds the recipe view titles from gui.yml: the placeholder substitution, the layout offset and icon tokens,
 * and the per-condition images of the special-recipe detail title.
 */
final class RecipeViewTitles {

    private final RecipeViewGui gui;

    RecipeViewTitles(RecipeViewGui gui) {
        this.gui = gui;
    }

    String menuTitle(String guiPath, String fallbackTitle, Map<String, String> placeholders) {
        String title = applyPlaceholders(fallbackTitle, placeholders);
        String offset = "";
        String icon = "";
        ConfigurationSection layout = layoutSection(guiPath);
        if (layout != null) {
            offset = layout.getString("offset", "");
            icon = layout.getString("icon", "");
        }
        return title.replace("<offset>", offset).replace("<icon>", icon);
    }

    // The title-layout craftengine section may define sunlight / water / catalyst_info image strings (each its
    // own shift + image); the matching placeholder in the title resolves to that string when the recipe has the
    // condition, or to the "<key>-off" string (a cover image) when it does not. One detail layout then shows
    // exactly the conditions each recipe declares.
    String specialDetailTitle(String guiPath, String fallbackTitle, Map<String, String> placeholders,
                              SpecialRecipeInfo info) {
        String title = applyPlaceholders(fallbackTitle, placeholders);
        String offset = "";
        String icon = "";
        String sunlight = "";
        String water = "";
        String catalystInfo = "";
        ConfigurationSection layout = layoutSection(guiPath);
        if (layout != null) {
            offset = layout.getString("offset", "");
            icon = layout.getString("icon", "");
            sunlight = conditionImage(layout, "sunlight", info != null && info.hasSunlight());
            water = conditionImage(layout, "water", info != null && info.hasWater());
            catalystInfo = conditionImage(layout, "catalyst_info", info != null && info.hasCatalystInfo());
        }
        return title
                .replace("<offset>", offset)
                .replace("<icon>", icon)
                .replace("<sunlight>", sunlight)
                .replace("<water>", water)
                .replace("<catalyst_info>", catalystInfo);
    }

    private ConfigurationSection layoutSection(String guiPath) {
        var guiSection = gui.plugin.getRecipeViewGuiSection();
        return guiSection != null
                ? guiSection.getConfigurationSection(guiPath + ".title-layout.craftengine")
                : null;
    }

    private static String applyPlaceholders(String title, Map<String, String> placeholders) {
        String result = title == null ? "GUI" : title;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    // The "on" image string for an active condition, or the "<key>-off" cover string for an inactive one;
    // both default to empty.
    private static String conditionImage(ConfigurationSection layout, String key, boolean active) {
        return active ? layout.getString(key, "") : layout.getString(key + "-off", "");
    }
}
