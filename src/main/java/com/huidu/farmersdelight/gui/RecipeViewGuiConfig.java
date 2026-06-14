package com.huidu.farmersdelight.gui;

import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RecipeViewGuiConfig {

    private final MainMenuConfig mainMenu;
    private final RecipeListConfig recipeList;
    private final RecipeDetailConfig cookingPotDetail;
    private final Map<String, RecipeDetailConfig> customCookingPotDetails;
    private final RecipeDetailConfig cuttingBoardDetail;
    private final boolean backgroundItemsEnabled;
    private final boolean showIngredientIds;
    private final int recipeListMaxPreviewIngredients;

    public RecipeViewGuiConfig(MainMenuConfig mainMenu, RecipeListConfig recipeList,
                               RecipeDetailConfig cookingPotDetail,
                               Map<String, RecipeDetailConfig> customCookingPotDetails,
                               RecipeDetailConfig cuttingBoardDetail,
                               boolean backgroundItemsEnabled, boolean showIngredientIds,
                               int recipeListMaxPreviewIngredients) {
        this.mainMenu = mainMenu;
        this.recipeList = recipeList;
        this.cookingPotDetail = cookingPotDetail;
        this.customCookingPotDetails = customCookingPotDetails != null
                ? Collections.unmodifiableMap(customCookingPotDetails)
                : Map.of();
        this.cuttingBoardDetail = cuttingBoardDetail;
        this.backgroundItemsEnabled = backgroundItemsEnabled;
        this.showIngredientIds = showIngredientIds;
        this.recipeListMaxPreviewIngredients = recipeListMaxPreviewIngredients;
    }

    public MainMenuConfig getMainMenu() {
        return mainMenu;
    }

    public RecipeListConfig getRecipeList() {
        return recipeList;
    }

    public RecipeDetailConfig getCookingPotDetail() {
        return cookingPotDetail;
    }

    public RecipeDetailConfig getCookingPotDetail(String customId) {
        if (customId == null || customId.isBlank()) {
            return cookingPotDetail;
        }
        RecipeDetailConfig custom = customCookingPotDetails.get(customId);
        return custom != null ? custom : cookingPotDetail;
    }

    public boolean hasCustomCookingPotDetail(String customId) {
        return customId != null && !customId.isBlank() && customCookingPotDetails.containsKey(customId);
    }

    public RecipeDetailConfig getCuttingBoardDetail() {
        return cuttingBoardDetail;
    }

    public boolean isBackgroundItemsEnabled() {
        return backgroundItemsEnabled;
    }

    public boolean isShowIngredientIds() {
        return showIngredientIds;
    }

    public int getRecipeListMaxPreviewIngredients() {
        return recipeListMaxPreviewIngredients;
    }

    public static RecipeViewGuiConfig fromConfig(ConfigurationSection section) {
        boolean backgroundItemsEnabled = section.getBoolean("background-items-enabled", true);
        boolean showIngredientIds = section.getBoolean("show-ingredient-ids", false);
        int maxPreviewIngredients = Math.max(1, section.getInt("max-preview-ingredients", 4));
        MainMenuConfig mainMenu = MainMenuConfig.fromConfig(section.getConfigurationSection("main-menu"));
        RecipeListConfig recipeList = RecipeListConfig.fromConfig(section.getConfigurationSection("recipe-list"));
        ConfigurationSection legacyDetail = section.getConfigurationSection("recipe-detail");
        RecipeDetailConfig cookingPotDetail = RecipeDetailConfig.fromConfig(
                section.getConfigurationSection("recipe-detail-cooking-pot"),
                legacyDetail,
                RecipeDetailConfig.createCookingPotDefault()
        );
        Map<String, RecipeDetailConfig> customCookingPotDetails = loadCustomCookingPotDetails(
                section.getConfigurationSection("recipe-detail-cooking-pot-guis"),
                cookingPotDetail
        );
        RecipeDetailConfig cuttingBoardDetail = RecipeDetailConfig.fromConfig(
                section.getConfigurationSection("recipe-detail-cutting-board"),
                legacyDetail,
                RecipeDetailConfig.createCuttingBoardDefault()
        );
        return new RecipeViewGuiConfig(mainMenu, recipeList, cookingPotDetail, customCookingPotDetails, cuttingBoardDetail,
                backgroundItemsEnabled, showIngredientIds, maxPreviewIngredients);
    }

    private static Map<String, RecipeDetailConfig> loadCustomCookingPotDetails(
            ConfigurationSection section,
            RecipeDetailConfig defaultConfig
    ) {
        if (section == null) {
            return Map.of();
        }
        Map<String, RecipeDetailConfig> configs = new HashMap<>();
        for (String id : section.getKeys(false)) {
            ConfigurationSection detailSection = section.getConfigurationSection(id);
            if (detailSection == null) {
                continue;
            }
            try {
                configs.put(id, RecipeDetailConfig.fromConfig(detailSection, null, defaultConfig));
            } catch (Exception e) {
                warnConfig("console.gui.custom_recipe_detail_load_failed",
                        "id", id,
                        "error", e.getMessage());
            }
        }
        return configs;
    }

    public static class BaseConfig {
        protected final String title;
        protected final int rows;
        protected final List<String> layout;
        protected final Map<Character, String> legend;
        protected final Map<String, GuiConfig.GuiItem> items;

        public BaseConfig(String title, int rows, List<String> layout,
                          Map<Character, String> legend, Map<String, GuiConfig.GuiItem> items) {
            this.title = title;
            this.rows = rows;
            this.layout = layout;
            this.legend = legend;
            this.items = items;
        }

        public String getTitle() {
            return title;
        }

        public int getRows() {
            return rows;
        }

        public int getSize() {
            return rows * 9;
        }

        public List<String> getLayout() {
            return layout;
        }

        public Map<Character, String> getLegend() {
            return legend;
        }

        public Map<String, GuiConfig.GuiItem> getItems() {
            return items;
        }

        public GuiConfig.GuiItem getItem(String key) {
            return items.get(key);
        }

        public String getSlotType(int slot) {
            int row = slot / 9;
            int col = slot % 9;

            if (row >= layout.size()) return null;

            String line = layout.get(row);
            if (col >= line.length()) return null;

            char c = line.charAt(col);
            return legend.get(c);
        }

        public List<Integer> getSlotsByType(String type) {
            List<Integer> slots = new ArrayList<>();
            for (int row = 0; row < layout.size(); row++) {
                String line = layout.get(row);
                for (int col = 0; col < line.length(); col++) {
                    char c = line.charAt(col);
                    String slotType = legend.get(c);
                    if (type.equals(slotType)) {
                        slots.add(row * 9 + col);
                    }
                }
            }
            return slots;
        }

        public int getFirstSlotByType(String type) {
            List<Integer> slots = getSlotsByType(type);
        if (slots.isEmpty()) {
            return -1;
        }
        return slots.get(0);
        }

        protected static BaseConfig parseConfig(ConfigurationSection section) {
            if (section == null) {
                return null;
            }

            String title = section.getString("title", "界面");
            int rows = section.getInt("rows", 3);
            List<String> layout = section.getStringList("layout");

            Map<Character, String> legend = new HashMap<>();
            ConfigurationSection legendSection = section.getConfigurationSection("legend");
            if (legendSection != null) {
                for (String key : legendSection.getKeys(false)) {
                    if (key.length() == 1) {
                        legend.put(key.charAt(0), legendSection.getString(key));
                    }
                }
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
            GuiConfig.inheritBackgroundVisualOptions(items);

            warnUnknownLayoutCharacters(section.getCurrentPath(), rows, layout, legend);
            return new BaseConfig(title, rows, layout, legend, items);
        }
    }

    private static void warnUnknownLayoutCharacters(
            String sectionPath,
            int rows,
            List<String> layout,
            Map<Character, String> legend
    ) {
        String path = sectionPath == null || sectionPath.isBlank() ? "recipe-view-gui" : sectionPath;
        if (layout.size() != rows) {
            warnConfig("console.gui.rows_mismatch",
                    "path", path,
                    "rows", rows,
                    "layout_rows", layout.size());
        }
        for (int row = 0; row < layout.size(); row++) {
            String line = layout.get(row);
            if (line.length() != 9) {
                warnConfig("console.gui.row_length_mismatch",
                        "path", path,
                        "row", row + 1,
                        "length", line.length());
            }
            for (int col = 0; col < line.length(); col++) {
                char c = line.charAt(col);
                if (!Character.isWhitespace(c) && !legend.containsKey(c)) {
                    warnConfig("console.gui.unknown_layout_character",
                            "path", path,
                            "character", c,
                            "row", row + 1,
                            "column", col + 1);
                }
            }
        }
    }

    private static void warnConfig(String key, Object... placeholders) {
        String message = I18n.formatNamedArgs(key, placeholders);
        com.huidu.farmersdelight.FarmersDelightPlugin plugin =
                com.huidu.farmersdelight.FarmersDelightPlugin.getInstance();
        if (plugin != null) {
            plugin.getLogger().warning(message);
        } else {
            org.bukkit.Bukkit.getLogger().warning(I18n.formatConsole("prefix") + " " + message);
        }
    }

    public static class MainMenuConfig extends BaseConfig {
        private final int cookingPotSlot;
        private final int cuttingBoardSlot;
        private final int backSlot;

        public MainMenuConfig(String title, int rows, List<String> layout,
                              Map<Character, String> legend, Map<String, GuiConfig.GuiItem> items,
                              int cookingPotSlot, int cuttingBoardSlot, int backSlot) {
            super(title, rows, layout, legend, items);
            this.cookingPotSlot = cookingPotSlot;
            this.cuttingBoardSlot = cuttingBoardSlot;
            this.backSlot = backSlot;
        }

        public int getCookingPotSlot() {
            return cookingPotSlot;
        }

        public int getCuttingBoardSlot() {
            return cuttingBoardSlot;
        }

        public int getBackSlot() {
            return backSlot;
        }

        public static MainMenuConfig fromConfig(ConfigurationSection section) {
            if (section == null) {
                return createDefault();
            }

            BaseConfig base = parseConfig(section);
            if (base == null) {
                return createDefault();
            }

            int cookingPotSlot = base.getFirstSlotByType("cooking_pot");
            int cuttingBoardSlot = base.getFirstSlotByType("cutting_board");
            int backSlot = base.getFirstSlotByType("back");

            return new MainMenuConfig(base.title, base.rows, base.layout, base.legend,
                    base.items, cookingPotSlot, cuttingBoardSlot, backSlot);
        }

        private static MainMenuConfig createDefault() {
            Map<Character, String> legend = new HashMap<>();
            legend.put('C', "cooking_pot");
            legend.put('D', "cutting_board");
            legend.put('B', "back");
            legend.put('X', "background");

            Map<String, GuiConfig.GuiItem> items = new HashMap<>();
            items.put("background", new GuiConfig.GuiItem(Material.GRAY_STAINED_GLASS_PANE, null, " ", List.of()));
            items.put("cooking_pot", new GuiConfig.GuiItem(Material.CAULDRON, null, "Cooking Pot Recipes", List.of("Click to view cooking pot recipes")));
            items.put("cutting_board", new GuiConfig.GuiItem(Material.BAMBOO_MOSAIC, null, "Cutting Board Recipes", List.of("Click to view cutting board recipes")));
            items.put("back", new GuiConfig.GuiItem(Material.BARRIER, null, "Close", List.of()));

            List<String> layout = List.of("XXXXBXXXX", "XXCXXXDXX", "XXXXXXXXX");
            return new MainMenuConfig("Recipe Viewer", 3, layout, legend, items, 11, 15, 4);
        }
    }

    public static class RecipeListConfig extends BaseConfig {
        private final List<Integer> recipeSlots;
        private final int prevPageSlot;
        private final int nextPageSlot;
        private final int backSlot;
        private final int infoSlot;
        private final int filterSlot;

        public RecipeListConfig(String title, int rows, List<String> layout,
                                Map<Character, String> legend, Map<String, GuiConfig.GuiItem> items,
                                List<Integer> recipeSlots, int prevPageSlot, int nextPageSlot,
                                int backSlot, int infoSlot, int filterSlot) {
            super(title, rows, layout, legend, items);
            this.recipeSlots = recipeSlots;
            this.prevPageSlot = prevPageSlot;
            this.nextPageSlot = nextPageSlot;
            this.backSlot = backSlot;
            this.infoSlot = infoSlot;
            this.filterSlot = filterSlot;
        }

        public List<Integer> getRecipeSlots() {
            return recipeSlots;
        }

        public int getPrevPageSlot() {
            return prevPageSlot;
        }

        public int getNextPageSlot() {
            return nextPageSlot;
        }

        public int getBackSlot() {
            return backSlot;
        }

        public int getInfoSlot() {
            return infoSlot;
        }

        public int getFilterSlot() {
            return filterSlot;
        }

        public static RecipeListConfig fromConfig(ConfigurationSection section) {
            if (section == null) {
                return createDefault();
            }

            BaseConfig base = parseConfig(section);
            if (base == null) {
                return createDefault();
            }

            List<Integer> recipeSlots = base.getSlotsByType("recipe");
            int prevPageSlot = base.getFirstSlotByType("prev_page");
            int nextPageSlot = base.getFirstSlotByType("next_page");
            int backSlot = base.getFirstSlotByType("back");
            int infoSlot = base.getFirstSlotByType("info");
            int filterSlot = base.getFirstSlotByType("filter");

            return new RecipeListConfig(base.title, base.rows, base.layout, base.legend,
                    base.items, recipeSlots, prevPageSlot, nextPageSlot, backSlot, infoSlot, filterSlot);
        }

        private static RecipeListConfig createDefault() {
            Map<Character, String> legend = new HashMap<>();
            legend.put('R', "recipe");
            legend.put('P', "prev_page");
            legend.put('N', "next_page");
            legend.put('B', "back");
            legend.put('F', "filter");
            legend.put('X', "background");

            Map<String, GuiConfig.GuiItem> items = new HashMap<>();
            items.put("background", new GuiConfig.GuiItem(Material.GRAY_STAINED_GLASS_PANE, null, " ", List.of()));
            items.put("prev_page", new GuiConfig.GuiItem(Material.ARROW, null, "Previous Page", List.of()));
            items.put("next_page", new GuiConfig.GuiItem(Material.ARROW, null, "Next Page", List.of()));
            items.put("back", new GuiConfig.GuiItem(Material.BARRIER, null, "Back", List.of()));
            items.put("info", new GuiConfig.GuiItem(Material.BOOK, null, "Recipe List", List.of("Page: {page}/{total}")));
            items.put("filter", new GuiConfig.GuiItem(Material.COMPASS, null, "Filter Craftable", List.of()));

            List<String> layout = List.of(
                    "PXFXBXXXN",
                    "RRRRRRRRR",
                    "RRRRRRRRR",
                    "RRRRRRRRR",
                    "RRRRRRRRR",
                    "RRRRRRRRR"
            );

            List<Integer> recipeSlots = new ArrayList<>();
            for (int i = 9; i < 54; i++) {
                recipeSlots.add(i);
            }

            return new RecipeListConfig("Recipe List", 6, layout, legend, items,
                    recipeSlots, 0, 8, 4, -1, 2);
        }
    }

    public static class RecipeDetailConfig extends BaseConfig {
        private final List<Integer> ingredientSlots;
        private final int inputSlot;
        private final List<Integer> resultSlots;
        private final int resultSlot;
        private final int containerSlot;
        private final int toolSlot;
        private final int arrowSlot;
        private final int progressSlot;
        private final int backSlot;
        private final int filterSlot;
        private final int materialsSlot;
        private final int fillSlot;

        public RecipeDetailConfig(String title, int rows, List<String> layout,
                                  Map<Character, String> legend, Map<String, GuiConfig.GuiItem> items,
                                  List<Integer> ingredientSlots, int inputSlot, List<Integer> resultSlots,
                                  int resultSlot, int containerSlot,
                                  int toolSlot, int arrowSlot, int progressSlot, int backSlot,
                                  int filterSlot, int materialsSlot, int fillSlot) {
            super(title, rows, layout, legend, items);
            this.ingredientSlots = ingredientSlots;
            this.inputSlot = inputSlot;
            this.resultSlots = resultSlots;
            this.resultSlot = resultSlot;
            this.containerSlot = containerSlot;
            this.toolSlot = toolSlot;
            this.arrowSlot = arrowSlot;
            this.progressSlot = progressSlot;
            this.backSlot = backSlot;
            this.filterSlot = filterSlot;
            this.materialsSlot = materialsSlot;
            this.fillSlot = fillSlot;
        }

        public List<Integer> getIngredientSlots() {
            return ingredientSlots;
        }

        public int getResultSlot() {
            return resultSlot;
        }

        public int getInputSlot() {
        if (inputSlot >= 0) {
            return inputSlot;
        }
        return resultSlot;
        }

        public List<Integer> getResultSlots() {
            if (resultSlots != null && !resultSlots.isEmpty()) {
                return resultSlots;
            }
            return ingredientSlots;
        }

        public int getContainerSlot() {
            return containerSlot;
        }

        public int getToolSlot() {
            return toolSlot;
        }

        public int getArrowSlot() {
            return arrowSlot;
        }

        // 进度条槽位（layout 中 "progress" 类型的首个槽位），解析时缓存，避免每次 GUI tick 重新扫描布局。
        public int getProgressSlot() {
            return progressSlot;
        }

        public int getBackSlot() {
            return backSlot;
        }

        public int getFilterSlot() {
            return filterSlot;
        }

        public int getMaterialsSlot() {
            return materialsSlot;
        }

        public int getFillSlot() {
            return fillSlot;
        }

        public static RecipeDetailConfig fromConfig(ConfigurationSection section,
                                                    ConfigurationSection fallbackSection,
                                                    RecipeDetailConfig defaultConfig) {
        ConfigurationSection actualSection = fallbackSection;
        if (section != null) {
            actualSection = section;
        }
            if (actualSection == null) {
                return defaultConfig;
            }

            BaseConfig base = parseConfig(actualSection);
            if (base == null) {
                return defaultConfig;
            }

            List<Integer> ingredientSlots = base.getSlotsByType("ingredient");
            int inputSlot = base.getFirstSlotByType("input");
            List<Integer> resultSlots = base.getSlotsByType("result");
            int resultSlot = base.getFirstSlotByType("result");
            int containerSlot = base.getFirstSlotByType("container");
            int toolSlot = base.getFirstSlotByType("tool");
            int arrowSlot = base.getFirstSlotByType("arrow");
            int progressSlot = base.getFirstSlotByType("progress");
            int backSlot = base.getFirstSlotByType("back");
            int filterSlot = base.getFirstSlotByType("filter");
            int materialsSlot = base.getFirstSlotByType("materials");
            int fillSlot = base.getFirstSlotByType("fill");

            return new RecipeDetailConfig(base.title, base.rows, base.layout, base.legend,
                    base.items, ingredientSlots, inputSlot, resultSlots, resultSlot, containerSlot, toolSlot, arrowSlot, progressSlot, backSlot,
                    filterSlot, materialsSlot, fillSlot);
        }

        static RecipeDetailConfig createCookingPotDefault() {
            Map<Character, String> legend = new HashMap<>();
            legend.put('R', "result");
            legend.put('I', "ingredient");
            legend.put('A', "arrow");
            legend.put('C', "container");
            legend.put('B', "back");
            legend.put('P', "fill");
            legend.put('G', "progress");
            legend.put('X', "background");

            Map<String, GuiConfig.GuiItem> items = new HashMap<>();
            items.put("background", new GuiConfig.GuiItem(Material.GRAY_STAINED_GLASS_PANE, null, " ", List.of()));
            items.put("arrow", new GuiConfig.GuiItem(Material.CLOCK, null, "Cooking Process",
                    List.of("&7Cook Time: &b{cook_time}", "&7Experience: &a{experience}")));
            items.put("back", new GuiConfig.GuiItem(Material.ARROW, null, "Back", List.of()));
            items.put("fill", new GuiConfig.GuiItem(Material.HOPPER, null, "Fill Ingredients", List.of()));

            List<String> layout = List.of(
                    "BXXXXXXPX",
                    "XXXXXAXXX",
                    "XIIIXGXXX",
                    "XIIIXXXXX",
                    "XXXXXCXRX",
                    "XXXXXXXXX"
            );

            List<Integer> ingredientSlots = List.of(19, 20, 21, 28, 29, 30);
            // progress 槽位对应 layout 中 'G'（第 3 行第 6 列 = 23），与 getFirstSlotByType("progress") 一致。
            return new RecipeDetailConfig("Recipe Details", 6, layout, legend, items,
                    ingredientSlots, -1, List.of(43), 43, 41, -1, 14, 23, 0, -1, -1, 7);
        }

        static RecipeDetailConfig createCuttingBoardDefault() {
            Map<Character, String> legend = new HashMap<>();
            legend.put('I', "input");
            legend.put('R', "result");
            legend.put('T', "tool");
            legend.put('B', "back");
            legend.put('X', "background");

            Map<String, GuiConfig.GuiItem> items = new HashMap<>();
            items.put("background", new GuiConfig.GuiItem(Material.GRAY_STAINED_GLASS_PANE, null, " ", List.of()));
            items.put("back", new GuiConfig.GuiItem(Material.ARROW, null, "Back", List.of()));

            List<String> layout = List.of(
                    "BXXXXXXXX",
                    "XXXXXXXXX",
                    "XXXXXXXXX",
                    "XIXTXRRXX",
                    "XXXXXRRXX",
                    "XXXXXXXXX"
            );

            List<Integer> resultSlots = List.of(32, 33, 41, 42);
            // 切菜板布局没有 progress 类型，progressSlot 为 -1，与 getFirstSlotByType("progress") 一致。
            return new RecipeDetailConfig("Recipe Details", 6, layout, legend, items,
                    List.of(), 28, resultSlots, 32, -1, 30, -1, -1, 0, -1, -1, -1);
        }
    }
}
