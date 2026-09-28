package com.huidu.farmersdelight.api.recipe;

import com.huidu.farmersdelight.api.config.ConfigSectionReader;
import com.huidu.farmersdelight.api.gui.GuiItems;
import com.huidu.farmersdelight.api.text.FarmersDelightText;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A plain-data RecipeBookLayout: title, row count, the character grid, the legend mapping each
 * character to an ingredient/recipe key, and any decoration items.
 *
 * <p>Build one with fromConfig; do not re-implement the parse. Sharing only the record was not
 * enough — every addon then wrote the same grid/legend/decoration reader, and the copies each lost
 * something FarmersDelight's own reader has (translation keys on decorations, the
 * title-layout.craftengine tokens, glyph resolution in the title).
 */
public record SimpleRecipeBookLayout(Component title, int rows, List<String> layout,
                                     Map<Character, String> legend, Map<String, ItemStack> decorations)
        implements RecipeBookLayout {

    /**
     * Reads a layout from one gui.yml section, falling back per field to fallback so a
     * config that only overrides the title keeps the built-in grid.
     *
     * <p>Reads title (MiniMessage; title-layout.craftengine.offset / .icon are
     * substituted into the <offset> / <icon> tokens first), rows (clamped to
     * 1-6), layout, legend and items.
     *
     * @param section  the section, or null to return fallback unchanged
     * @param fallback the built-in layout; must not be null
     */
    public static SimpleRecipeBookLayout fromConfig(ConfigurationSection section,
                                                    SimpleRecipeBookLayout fallback) {
        if (section == null) {
            return fallback;
        }
        String rawTitle = ConfigSectionReader.optionalString(section, "title");
        Component title = rawTitle == null
                ? fallback.title()
                : FarmersDelightText.deserializeGlyphsTitle(applyTitleLayout(section, rawTitle));

        int rows = Math.max(1, Math.min(6, ConfigSectionReader.optionalInt(section, "rows", fallback.rows())));

        List<String> layout = ConfigSectionReader.optionalStringList(section, "layout");
        if (layout.isEmpty()) {
            layout = fallback.layout();
        }

        Map<Character, String> legend = new HashMap<>();
        ConfigurationSection legendSection = section.getConfigurationSection("legend");
        if (legendSection != null) {
            for (String key : legendSection.getKeys(false)) {
                if (key.length() == 1) {
                    legend.put(key.charAt(0), ConfigSectionReader.optionalString(legendSection, key));
                }
            }
        }
        if (legend.isEmpty()) {
            legend = fallback.legend();
        }

        Map<String, ItemStack> decorations = new HashMap<>();
        ConfigurationSection itemsSection = section.getConfigurationSection("items");
        if (itemsSection != null) {
            for (String key : itemsSection.getKeys(false)) {
                ItemStack built = GuiItems.build(itemsSection.getConfigurationSection(key));
                if (built != null) {
                    decorations.put(key, built);
                }
            }
        }
        if (decorations.isEmpty()) {
            decorations = fallback.decorations();
        }

        return new SimpleRecipeBookLayout(title, rows, layout, legend, decorations);
    }

    /**
     * Substitutes the CraftEngine offset/icon tokens declared under title-layout.craftengine.
     * Other tokens are left in place for the caller to resolve (the keg book's <temperature>,
     * for instance, is per-recipe and cannot be baked into a static layout).
     */
    public static String applyTitleLayout(ConfigurationSection section, String rawTitle) {
        if (rawTitle == null) {
            return null;
        }
        ConfigurationSection craftEngine = section == null
                ? null
                : section.getConfigurationSection("title-layout.craftengine");
        if (craftEngine == null) {
            return rawTitle;
        }
        String offset = ConfigSectionReader.optionalString(craftEngine, "offset", "");
        String icon = ConfigSectionReader.optionalString(craftEngine, "icon", "");
        return rawTitle.replace("<offset>", offset == null ? "" : offset)
                .replace("<icon>", icon == null ? "" : icon);
    }

    /**
     * The stock recipe browser: five rows of recipe cells over a previous / back / next footer. A station
     * that has no layout of its own uses this so every addon book opens with the same grid; only the
     * title differs, and it is the caller's.
     */
    public static SimpleRecipeBookLayout defaultBrowser(Component title) {
        Map<Character, String> legend = new HashMap<>();
        legend.put('R', "recipe");
        legend.put('P', "prev_page");
        legend.put('N', "next_page");
        legend.put('B', "back");
        legend.put('X', "background");
        Map<String, ItemStack> decorations = new HashMap<>();
        decorations.put("background", namedItem(Material.GRAY_STAINED_GLASS_PANE, " ", true));
        decorations.put("prev_page", namedItem(Material.ARROW, "Previous", false));
        decorations.put("next_page", namedItem(Material.ARROW, "Next", false));
        decorations.put("back", namedItem(Material.BARRIER, "Close", false));
        List<String> layout = List.of(
                "RRRRRRRRR",
                "RRRRRRRRR",
                "RRRRRRRRR",
                "RRRRRRRRR",
                "RRRRRRRRR",
                "PXXXBXXXN");
        return new SimpleRecipeBookLayout(title, 6, layout, legend, decorations);
    }

    /** A GUI icon with a non-italic name and an optional hidden tooltip. */
    public static ItemStack namedItem(Material material, String name, boolean hideTooltip) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name).decoration(TextDecoration.ITALIC, false));
            if (hideTooltip) {
                meta.setHideTooltip(true);
            }
            item.setItemMeta(meta);
        }
        return item;
    }
}
