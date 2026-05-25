package com.huidu.farmersdelight.gui;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RecipeViewGuiConfig {

    private final MainMenuConfig mainMenu;
    private final RecipeListConfig recipeList;
    private final RecipeDetailConfig cookingPotDetail;
    private final RecipeDetailConfig cuttingBoardDetail;
    private final boolean backgroundItemsEnabled;
    private final boolean showIngredientIds;

    public RecipeViewGuiConfig(MainMenuConfig mainMenu, RecipeListConfig recipeList,
                               RecipeDetailConfig cookingPotDetail, RecipeDetailConfig cuttingBoardDetail,
                               boolean backgroundItemsEnabled, boolean showIngredientIds) {
        this.mainMenu = mainMenu;
        this.recipeList = recipeList;
        this.cookingPotDetail = cookingPotDetail;
        this.cuttingBoardDetail = cuttingBoardDetail;
        this.backgroundItemsEnabled = backgroundItemsEnabled;
        this.showIngredientIds = showIngredientIds;
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

    public RecipeDetailConfig getCuttingBoardDetail() {
        return cuttingBoardDetail;
    }

    public boolean isBackgroundItemsEnabled() {
        return backgroundItemsEnabled;
    }

    public boolean isShowIngredientIds() {
        return showIngredientIds;
    }

    public static RecipeViewGuiConfig fromConfig(ConfigurationSection section) {
        boolean backgroundItemsEnabled = section.getBoolean("background-items-enabled", true);
        boolean showIngredientIds = section.getBoolean("show-ingredient-ids", false);
        MainMenuConfig mainMenu = MainMenuConfig.fromConfig(section.getConfigurationSection("main-menu"));
        RecipeListConfig recipeList = RecipeListConfig.fromConfig(section.getConfigurationSection("recipe-list"));
        ConfigurationSection legacyDetail = section.getConfigurationSection("recipe-detail");
        RecipeDetailConfig cookingPotDetail = RecipeDetailConfig.fromConfig(
                section.getConfigurationSection("recipe-detail-cooking-pot"),
                legacyDetail,
                RecipeDetailConfig.createCookingPotDefault()
        );
        RecipeDetailConfig cuttingBoardDetail = RecipeDetailConfig.fromConfig(
                section.getConfigurationSection("recipe-detail-cutting-board"),
                legacyDetail,
                RecipeDetailConfig.createCuttingBoardDefault()
        );
        return new RecipeViewGuiConfig(mainMenu, recipeList, cookingPotDetail, cuttingBoardDetail,
                backgroundItemsEnabled, showIngredientIds);
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

            if (row >= layout.size()) return "background";

            String line = layout.get(row);
            if (col >= line.length()) return "background";

            char c = line.charAt(col);
            return legend.getOrDefault(c, "background");
        }

        public List<Integer> getSlotsByType(String type) {
            List<Integer> slots = new ArrayList<>();
            for (int row = 0; row < layout.size(); row++) {
                String line = layout.get(row);
                for (int col = 0; col < line.length(); col++) {
                    char c = line.charAt(col);
                    String slotType = legend.getOrDefault(c, "background");
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

            String title = section.getString("title", "GUI");
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

            return new BaseConfig(title, rows, layout, legend, items);
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
            legend.put('#', "background");

            Map<String, GuiConfig.GuiItem> items = new HashMap<>();
            items.put("background", new GuiConfig.GuiItem(Material.GRAY_STAINED_GLASS_PANE, null, " ", List.of()));
            items.put("cooking_pot", new GuiConfig.GuiItem(Material.CAULDRON, null, "Cooking Pot Recipes", List.of("Click to view cooking pot recipes")));
            items.put("cutting_board", new GuiConfig.GuiItem(Material.BAMBOO_MOSAIC, null, "Cutting Board Recipes", List.of("Click to view cutting board recipes")));
            items.put("back", new GuiConfig.GuiItem(Material.BARRIER, null, "Close", List.of()));

            List<String> layout = List.of("####B####", "##C###D##", "####X####");
            return new MainMenuConfig("Recipe Viewer", 3, layout, legend, items, 11, 15, 4);
        }
    }

    public static class RecipeListConfig extends BaseConfig {
        private final List<Integer> recipeSlots;
        private final int prevPageSlot;
        private final int nextPageSlot;
        private final int backSlot;
        private final int infoSlot;

        public RecipeListConfig(String title, int rows, List<String> layout,
                                Map<Character, String> legend, Map<String, GuiConfig.GuiItem> items,
                                List<Integer> recipeSlots, int prevPageSlot, int nextPageSlot,
                                int backSlot, int infoSlot) {
            super(title, rows, layout, legend, items);
            this.recipeSlots = recipeSlots;
            this.prevPageSlot = prevPageSlot;
            this.nextPageSlot = nextPageSlot;
            this.backSlot = backSlot;
            this.infoSlot = infoSlot;
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

            return new RecipeListConfig(base.title, base.rows, base.layout, base.legend,
                    base.items, recipeSlots, prevPageSlot, nextPageSlot, backSlot, infoSlot);
        }

        private static RecipeListConfig createDefault() {
            Map<Character, String> legend = new HashMap<>();
            legend.put('R', "recipe");
            legend.put('P', "prev_page");
            legend.put('N', "next_page");
            legend.put('B', "back");
            legend.put('X', "info");
            legend.put('#', "background");

            Map<String, GuiConfig.GuiItem> items = new HashMap<>();
            items.put("background", new GuiConfig.GuiItem(Material.GRAY_STAINED_GLASS_PANE, null, " ", List.of()));
            items.put("prev_page", new GuiConfig.GuiItem(Material.ARROW, null, "Previous Page", List.of()));
            items.put("next_page", new GuiConfig.GuiItem(Material.ARROW, null, "Next Page", List.of()));
            items.put("back", new GuiConfig.GuiItem(Material.BARRIER, null, "Back", List.of()));
            items.put("info", new GuiConfig.GuiItem(Material.BOOK, null, "Recipe List", List.of("Page: {page}/{total}")));

            List<String> layout = List.of(
                    "P###B###N",
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
                    recipeSlots, 0, 8, 4, -1);
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
        private final int backSlot;

        public RecipeDetailConfig(String title, int rows, List<String> layout,
                                  Map<Character, String> legend, Map<String, GuiConfig.GuiItem> items,
                                  List<Integer> ingredientSlots, int inputSlot, List<Integer> resultSlots,
                                  int resultSlot, int containerSlot,
                                  int toolSlot, int arrowSlot, int backSlot) {
            super(title, rows, layout, legend, items);
            this.ingredientSlots = ingredientSlots;
            this.inputSlot = inputSlot;
            this.resultSlots = resultSlots;
            this.resultSlot = resultSlot;
            this.containerSlot = containerSlot;
            this.toolSlot = toolSlot;
            this.arrowSlot = arrowSlot;
            this.backSlot = backSlot;
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

        public int getBackSlot() {
            return backSlot;
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
            int backSlot = base.getFirstSlotByType("back");

            return new RecipeDetailConfig(base.title, base.rows, base.layout, base.legend,
                    base.items, ingredientSlots, inputSlot, resultSlots, resultSlot, containerSlot, toolSlot, arrowSlot, backSlot);
        }

        static RecipeDetailConfig createCookingPotDefault() {
            Map<Character, String> legend = new HashMap<>();
            legend.put('R', "result");
            legend.put('I', "ingredient");
            legend.put('A', "arrow");
            legend.put('C', "container");
            legend.put('B', "back");
            legend.put('#', "background");

            Map<String, GuiConfig.GuiItem> items = new HashMap<>();
            items.put("background", new GuiConfig.GuiItem(Material.GRAY_STAINED_GLASS_PANE, null, " ", List.of()));
            items.put("arrow", new GuiConfig.GuiItem(Material.CLOCK, null, "Cooking Process", List.of()));
            items.put("back", new GuiConfig.GuiItem(Material.ARROW, null, "Back", List.of()));

            List<String> layout = List.of(
                    "B########",
                    "#####A###",
                    "#III#####",
                    "#III#####",
                    "#####C#R#",
                    "#########"
            );

            List<Integer> ingredientSlots = List.of(19, 20, 21, 28, 29, 30);
            return new RecipeDetailConfig("Recipe Details", 6, layout, legend, items,
                    ingredientSlots, -1, List.of(43), 43, 41, -1, 14, 0);
        }

        static RecipeDetailConfig createCuttingBoardDefault() {
            Map<Character, String> legend = new HashMap<>();
            legend.put('I', "input");
            legend.put('R', "result");
            legend.put('T', "tool");
            legend.put('B', "back");
            legend.put('#', "background");

            Map<String, GuiConfig.GuiItem> items = new HashMap<>();
            items.put("background", new GuiConfig.GuiItem(Material.GRAY_STAINED_GLASS_PANE, null, " ", List.of()));
            items.put("back", new GuiConfig.GuiItem(Material.ARROW, null, "Back", List.of()));

            List<String> layout = List.of(
                    "B########",
                    "#########",
                    "#########",
                    "#I#T#RR##",
                    "#####RR##",
                    "#########"
            );

            List<Integer> resultSlots = List.of(32, 33, 41, 42);
            return new RecipeDetailConfig("Recipe Details", 6, layout, legend, items,
                    List.of(), 28, resultSlots, 32, -1, 30, -1, 0);
        }
    }
}

