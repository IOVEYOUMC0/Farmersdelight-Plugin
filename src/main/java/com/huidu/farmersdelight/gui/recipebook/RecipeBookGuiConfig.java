package com.huidu.farmersdelight.gui.recipebook;

import com.huidu.farmersdelight.gui.GuiConfig;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Config for the generic addon recipe book (RecipeBookGui) — the category menu, paginated recipe
 * list, and recipe detail views. Read from gui.yml -> recipe-book-gui.{menu,list,detail};
 * defaults reproduce the previous hardcoded layout, so behavior is unchanged until edited.
 */
public final class RecipeBookGuiConfig {

    // Slot types whose content is filled dynamically (categories/recipes/inputs/result) or conditionally
    // (page arrows, fill button) — chrome rendering skips these; everything else with an item is static chrome.
    private static final Set<String> DYNAMIC_OR_CONDITIONAL = Set.of(
            "category", "recipe", "ingredient", "result", "prev_page", "next_page", "fill");

    private final ViewConfig menu;
    private final ViewConfig list;
    private final ViewConfig detail;

    public RecipeBookGuiConfig(ViewConfig menu, ViewConfig list, ViewConfig detail) {
        this.menu = menu;
        this.list = list;
        this.detail = detail;
    }

    public ViewConfig menu() {
        return menu;
    }

    public ViewConfig list() {
        return list;
    }

    public ViewConfig detail() {
        return detail;
    }

    public static RecipeBookGuiConfig fromConfig(ConfigurationSection section) {
        ConfigurationSection menuSection = section == null ? null : section.getConfigurationSection("menu");
        ConfigurationSection listSection = section == null ? null : section.getConfigurationSection("list");
        ConfigurationSection detailSection = section == null ? null : section.getConfigurationSection("detail");
        return new RecipeBookGuiConfig(
                ViewConfig.fromConfig(menuSection, defaultMenu()),
                ViewConfig.fromConfig(listSection, defaultList()),
                ViewConfig.fromConfig(detailSection, defaultDetail()));
    }

    /** A single view (menu / list / detail): title, layout, legend, items + typed slot lookups. */
    public static final class ViewConfig {
        private final String title;
        private final int rows;
        private final List<String> layout;
        private final Map<Character, String> legend;
        private final Map<String, GuiConfig.GuiItem> items;

        public ViewConfig(String title, int rows, List<String> layout,
                          Map<Character, String> legend, Map<String, GuiConfig.GuiItem> items) {
            this.title = title;
            this.rows = rows;
            this.layout = layout;
            this.legend = legend;
            this.items = items;
        }

        public String title() {
            return title;
        }

        public int size() {
            return rows * 9;
        }

        public GuiConfig.GuiItem item(String key) {
            return items.get(key);
        }

        public List<Integer> slotsByType(String type) {
            List<Integer> slots = new ArrayList<>();
            for (int row = 0; row < layout.size(); row++) {
                String line = layout.get(row);
                for (int col = 0; col < line.length(); col++) {
                    if (type.equals(legend.get(line.charAt(col)))) {
                        slots.add(row * 9 + col);
                    }
                }
            }
            return slots;
        }

        public int firstSlotByType(String type) {
            List<Integer> slots = slotsByType(type);
            return slots.isEmpty() ? -1 : slots.get(0);
        }

        /** Renders static chrome (background, decoration, close/back, etc.) into every layout slot whose type
         * has an item, skipping dynamic/conditional types (those are placed by the GUI). */
        public void renderChrome(Inventory inventory) {
            for (int row = 0; row < layout.size(); row++) {
                String line = layout.get(row);
                for (int col = 0; col < line.length(); col++) {
                    int slot = row * 9 + col;
                    if (slot >= inventory.getSize()) {
                        continue;
                    }
                    String type = legend.get(line.charAt(col));
                    if (type == null || DYNAMIC_OR_CONDITIONAL.contains(type)) {
                        continue;
                    }
                    GuiConfig.GuiItem item = items.get(type);
                    if (item != null) {
                        inventory.setItem(slot, item.createItem());
                    }
                }
            }
        }

        static ViewConfig fromConfig(ConfigurationSection section, ViewConfig fallback) {
            if (section == null) {
                return fallback;
            }
            String title = section.getString("title", fallback.title);
            int rows = section.getInt("rows", fallback.rows);
            List<String> layout = section.getStringList("layout");
            if (layout.isEmpty()) {
                layout = fallback.layout;
            }
            Map<Character, String> legend = new HashMap<>();
            ConfigurationSection legendSection = section.getConfigurationSection("legend");
            if (legendSection != null) {
                for (String key : legendSection.getKeys(false)) {
                    if (key.length() == 1) {
                        legend.put(key.charAt(0), legendSection.getString(key));
                    }
                }
            }
            if (legend.isEmpty()) {
                legend = fallback.legend;
            }
            Map<String, GuiConfig.GuiItem> items = new HashMap<>();
            ConfigurationSection itemsSection = section.getConfigurationSection("items");
            if (itemsSection != null) {
                for (String key : itemsSection.getKeys(false)) {
                    ConfigurationSection itemSection = itemsSection.getConfigurationSection(key);
                    if (itemSection != null) {
                        items.put(key, GuiConfig.GuiItem.fromConfig(itemSection));
                    }
                }
            }
            if (items.isEmpty()) {
                items = fallback.items;
            }
            return new ViewConfig(title, rows, layout, legend, items);
        }
    }

    private static ViewConfig defaultMenu() {
        Map<Character, String> legend = new HashMap<>();
        legend.put('C', "category");
        legend.put('B', "back");
        legend.put('X', "background");
        Map<String, GuiConfig.GuiItem> items = new HashMap<>();
        items.put("background", new GuiConfig.GuiItem(Material.GRAY_STAINED_GLASS_PANE, null, " ", List.of()));
        items.put("back", new GuiConfig.GuiItem(Material.BARRIER, null, "Close", List.of()));
        List<String> layout = List.of(
                "XXXXXXXXX",
                "XCCCCCCCX",
                "XCCCCCCCX",
                "XCCCCCCCX",
                "XCCCCCCCX",
                "XXXXBXXXX");
        return new ViewConfig("Recipes", 6, layout, legend, items);
    }

    private static ViewConfig defaultList() {
        Map<Character, String> legend = new HashMap<>();
        legend.put('R', "recipe");
        legend.put('P', "prev_page");
        legend.put('N', "next_page");
        legend.put('B', "back");
        legend.put('X', "background");
        Map<String, GuiConfig.GuiItem> items = new HashMap<>();
        items.put("prev_page", new GuiConfig.GuiItem(Material.ARROW, null, "Previous", List.of()));
        items.put("next_page", new GuiConfig.GuiItem(Material.ARROW, null, "Next", List.of()));
        items.put("back", new GuiConfig.GuiItem(Material.BARRIER, null, "Back", List.of()));
        List<String> layout = List.of(
                "RRRRRRRRR",
                "RRRRRRRRR",
                "RRRRRRRRR",
                "RRRRRRRRR",
                "RRRRRRRRR",
                "PXXXBXXXN");
        return new ViewConfig("Recipes", 6, layout, legend, items);
    }

    private static ViewConfig defaultDetail() {
        Map<Character, String> legend = new HashMap<>();
        legend.put('I', "ingredient");
        legend.put('R', "result");
        legend.put('F', "fill");
        legend.put('B', "back");
        legend.put('X', "background");
        Map<String, GuiConfig.GuiItem> items = new HashMap<>();
        items.put("fill", new GuiConfig.GuiItem(Material.HOPPER, null, "Fill ingredients", List.of()));
        items.put("back", new GuiConfig.GuiItem(Material.ARROW, null, "Back", List.of()));
        List<String> layout = List.of(
                "XXXXXXXXX",
                "XIIIIIIRX",
                "XXXXXXXXX",
                "XXXXXXXXX",
                "XXXXXXXXX",
                "XXXXBXXFX");
        return new ViewConfig("Recipes", 6, layout, legend, items);
    }
}
