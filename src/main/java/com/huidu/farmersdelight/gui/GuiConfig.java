package com.huidu.farmersdelight.gui;

import com.huidu.farmersdelight.i18n.I18n;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GuiConfig {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final String title;
    private final String titleLayoutOffset;
    private final String titleLayoutIcon;
    private final boolean fillersEnabled;
    private final int rows;
    private final List<String> layout;
    private final Map<Character, String> legend;
    private final Map<String, GuiItem> items;

    private final int[] ingredientSlots;
    private final int heatSlot;
    private final int containerSlot;
    private final int progressSlot;
    private final int bufferSlot;
    private final int outputSlot;
    private final int recipeSlot;
    private final List<GuiItem> progressItems;

    public GuiConfig(String title, String titleLayoutOffset, String titleLayoutIcon, boolean fillersEnabled,
                     int rows, List<String> layout,
                     Map<Character, String> legend, Map<String, GuiItem> items,
                     List<GuiItem> progressItems) {
        this.title = title;
        this.titleLayoutOffset = titleLayoutOffset;
        this.titleLayoutIcon = titleLayoutIcon;
        this.fillersEnabled = fillersEnabled;
        this.rows = rows;
        this.layout = layout;
        this.legend = legend;
        this.items = items;
        if (progressItems != null) {
            this.progressItems = progressItems;
        } else {
            this.progressItems = new ArrayList<>();
        }

        List<Integer> ingredients = new ArrayList<>();
        int heat = -1;
        int container = -1;
        int progress = -1;
        int buffer = -1;
        int output = -1;
        int recipe = -1;

        for (int row = 0; row < layout.size(); row++) {
            String line = layout.get(row);
            for (int col = 0; col < line.length(); col++) {
                char c = line.charAt(col);
                int slot = row * 9 + col;
                String type = legend.get(c);

                if (type != null) {
                    switch (type) {
                        case "ingredient" -> ingredients.add(slot);
                        case "heat" -> heat = slot;
                        case "container" -> container = slot;
                        case "progress" -> progress = slot;
                        case "meal", "buffer" -> buffer = slot;
                        case "output" -> output = slot;
                        case "recipe" -> recipe = slot;
                        default -> {
                        }
                    }
                }
            }
        }

        this.ingredientSlots = ingredients.stream().mapToInt(i -> i).toArray();
        this.heatSlot = heat;
        this.containerSlot = container;
        this.progressSlot = progress;
        this.bufferSlot = buffer;
        this.outputSlot = output;
        this.recipeSlot = recipe;
    }

    public static GuiConfig fromConfig(ConfigurationSection section) {
        String title = section.getString("title", "GUI");
        String titleLayoutOffset = section.getString("title-layout.craftengine.offset", "");
        String titleLayoutIcon = section.getString("title-layout.craftengine.icon", "");
        boolean fillersEnabled = section.getBoolean("fillers-enabled", true);
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

        Map<String, GuiItem> items = new HashMap<>();
        ConfigurationSection itemsSection = section.getConfigurationSection("items");
        if (itemsSection != null) {
            for (String key : itemsSection.getKeys(false)) {
                ConfigurationSection itemSection = itemsSection.getConfigurationSection(key);
                if (itemSection != null) {
                    items.put(key, GuiItem.fromConfig(itemSection));
                }
            }
        }

        List<GuiItem> progressItems = new ArrayList<>();
        List<Map<?, ?>> progressItemsList = section.getMapList("progress-items");
        if (progressItemsList.isEmpty() && itemsSection != null) {
            progressItemsList = itemsSection.getMapList("progress-items");
        }
        for (Map<?, ?> itemMap : progressItemsList) {
            GuiItem progressItem = GuiItem.fromMap(itemMap);
            if (progressItem != null) {
                progressItems.add(progressItem);
            }
        }

        return new GuiConfig(title, titleLayoutOffset, titleLayoutIcon, fillersEnabled, rows, layout, legend, items,
                progressItems);
    }

    public static GuiConfig createDefault() {
        List<String> layout = List.of(
                "XIIIXPXBX",
                "RIIIXXXXX",
                "XXHXXCXOX"
        );

        Map<Character, String> legend = new HashMap<>();
        legend.put('I', "ingredient");
        legend.put('H', "heat");
        legend.put('C', "container");
        legend.put('P', "progress");
        legend.put('B', "buffer");
        legend.put('O', "output");
        legend.put('R', "recipe");
        legend.put('X', "decoration");
        legend.put(' ', "background");

        Map<String, GuiItem> items = new HashMap<>();
        items.put("background", new GuiItem(Material.GRAY_STAINED_GLASS_PANE, null, " ", List.of()));
        items.put("decoration", new GuiItem(Material.BROWN_STAINED_GLASS_PANE, null, " ", List.of()));
        items.put("recipe", new GuiItem(Material.KNOWLEDGE_BOOK, null, "View Recipes", List.of("Click to view all cooking pot recipes")));

        return new GuiConfig(
                "<white><offset><icon>",
                "<shift:-8>",
                "<image:farmersdelight:cooking_pot_gui>",
                true,
                3,
                layout,
                legend,
                items,
                new ArrayList<>()
        );
    }

    public String getTitle() {
        return title;
    }

    public String getTitleLayoutOffset() {
        return titleLayoutOffset;
    }

    public String getTitleLayoutIcon() {
        return titleLayoutIcon;
    }

    public int getRows() {
        return rows;
    }

    public boolean isFillersEnabled() {
        return fillersEnabled;
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

    public Map<String, GuiItem> getItems() {
        return items;
    }

    public int[] getIngredientSlots() {
        return ingredientSlots;
    }

    public int getHeatSlot() {
        return heatSlot;
    }

    public int getContainerSlot() {
        return containerSlot;
    }

    public int getProgressSlot() {
        return progressSlot;
    }

    public int getMealSlot() {
        return bufferSlot;
    }

    public int getBufferSlot() {
        return bufferSlot;
    }

    public int getOutputSlot() {
        return outputSlot;
    }

    public int getRecipeSlot() {
        return recipeSlot;
    }

    public GuiItem getItem(String key) {
        return items.get(key);
    }

    public List<GuiItem> getProgressItems() {
        return progressItems;
    }

    public GuiItem getProgressItem(int percent) {
        if (progressItems.isEmpty()) {
            return getItem("progress");
        }
        int index = Math.min(Math.max(0, percent / 5), progressItems.size() - 1);
        return progressItems.get(index);
    }

    public String getSlotType(int slot) {
        int row = slot / 9;
        int col = slot % 9;

        if (row >= layout.size()) {
            return "background";
        }

        String line = layout.get(row);
        if (col >= line.length()) {
            return "background";
        }

        char c = line.charAt(col);
        return legend.getOrDefault(c, "background");
    }

    public boolean isIngredientSlot(int slot) {
        for (int s : ingredientSlots) {
            if (s == slot) {
                return true;
            }
        }
        return false;
    }

    public boolean isContainerSlot(int slot) {
        return slot == containerSlot;
    }

    public boolean isMealSlot(int slot) {
        return slot == bufferSlot;
    }

    public boolean isBufferSlot(int slot) {
        return slot == bufferSlot;
    }

    public boolean isOutputSlot(int slot) {
        return slot == outputSlot;
    }

    public boolean isHeatSlot(int slot) {
        return slot == heatSlot;
    }

    public boolean isProgressSlot(int slot) {
        return slot == progressSlot;
    }

    public boolean isRecipeSlot(int slot) {
        return slot == recipeSlot;
    }

    public boolean isInteractiveSlot(int slot) {
        String type = getSlotType(slot);
        return "ingredient".equals(type) || "container".equals(type);
    }

    public static class GuiItem {
        private final Material material;
        private final Key customItemId;
        private final Integer customModelData;
        private final String name;
        private final List<String> lore;
        private final String nameKey;
        private final List<String> loreKeys;

        public GuiItem(Material material, Key customItemId, String name, List<String> lore) {
            this(material, customItemId, null, name, lore);
        }

        public GuiItem(Material material, Key customItemId, Integer customModelData, String name, List<String> lore) {
            this(material, customItemId, customModelData, name, lore, null, List.of());
        }

        public GuiItem(Material material, Key customItemId, Integer customModelData, String name, List<String> lore,
                       String nameKey, List<String> loreKeys) {
            this.material = material;
            this.customItemId = customItemId;
            this.customModelData = customModelData;
            this.name = name;
            this.lore = lore;
            this.nameKey = nameKey;
            if (loreKeys != null) {
                this.loreKeys = loreKeys;
            } else {
                this.loreKeys = List.of();
            }
        }

        public static GuiItem fromConfig(ConfigurationSection section) {
            String materialName = section.getString("material");
            Material material = null;
            if (materialName != null && !materialName.isEmpty()) {
                try {
                    material = Material.valueOf(materialName.toUpperCase());
                } catch (IllegalArgumentException e) {
                    material = Material.GRAY_STAINED_GLASS_PANE;
                }
            }

            String customItemIdStr = section.getString("item");
            Key customItemId = null;
            if (customItemIdStr != null && !customItemIdStr.isEmpty()) {
                customItemId = Key.of(customItemIdStr);
            }

            if (material == null && customItemId == null) {
                material = Material.GRAY_STAINED_GLASS_PANE;
            }

            String name = section.getString("name", " ");
            List<String> lore = section.getStringList("lore");
            String nameKey = section.getString("name-key");
            List<String> loreKeys = section.getStringList("lore-keys");
            Integer customModelData = null;
            if (section.contains("custom-model-data")) {
                customModelData = section.getInt("custom-model-data");
            } else if (section.contains("customModelData")) {
                customModelData = section.getInt("customModelData");
            }

            return new GuiItem(material, customItemId, customModelData, name, lore, nameKey, loreKeys);
        }

        public static GuiItem fromMap(Map<?, ?> map) {
            if (map == null || map.isEmpty()) {
                return null;
            }

            Material material = null;
            Object materialValue = map.get("material");
            if (materialValue != null) {
                try {
                    material = Material.valueOf(materialValue.toString().toUpperCase());
                } catch (IllegalArgumentException ignored) {
                    material = Material.GRAY_STAINED_GLASS_PANE;
                }
            }

            Key customItemId = null;
            Object itemValue = map.get("item");
            if (itemValue != null && !itemValue.toString().isEmpty()) {
                customItemId = Key.of(itemValue.toString());
            }

            if (material == null && customItemId == null) {
                return null;
            }

                String name = null;
                if (map.get("name") != null) {
                    name = map.get("name").toString();
                }
                String nameKey = null;
                if (map.get("name-key") != null) {
                    nameKey = map.get("name-key").toString();
                }
            Integer customModelData = null;
            Object customModelDataValue = map.containsKey("custom-model-data")
                    ? map.get("custom-model-data")
                    : map.get("customModelData");
            if (customModelDataValue != null) {
                try {
                    customModelData = Integer.parseInt(customModelDataValue.toString());
                } catch (NumberFormatException ignored) {
                    customModelData = null;
                }
            }
            List<String> lore = new ArrayList<>();
            Object loreValue = map.get("lore");
            if (loreValue instanceof List<?> loreList) {
                for (Object line : loreList) {
                    if (line != null) {
                        lore.add(line.toString());
                    }
                }
            }
            List<String> loreKeys = new ArrayList<>();
            Object loreKeysValue = map.get("lore-keys");
            if (loreKeysValue instanceof List<?> loreKeysList) {
                for (Object key : loreKeysList) {
                    if (key != null) {
                        loreKeys.add(key.toString());
                    }
                }
            }

            return new GuiItem(material, customItemId, customModelData, name, lore, nameKey, loreKeys);
        }

        public Material getMaterial() {
            return material;
        }

        public Key getCustomItemId() {
            return customItemId;
        }

        public Integer getCustomModelData() {
            return customModelData;
        }

        public boolean isCustomItem() {
            return customItemId != null;
        }

        public String getName() {
            return name;
        }

        public List<String> getLore() {
            return lore;
        }

        public ItemStack createItem() {
            return createItem(new HashMap<>());
        }

        public ItemStack createItem(Map<String, String> placeholders) {
            ItemStack item;
            boolean builtFromCustomItem = false;

            if (customItemId != null) {
                BukkitItemManager itemManager = BukkitItemManager.instance();
                item = itemManager.buildCustomItemStack(customItemId, null);
                if (item == null) {
                    Material resolvedMaterial = material;
                    if (resolvedMaterial == null) {
                        resolvedMaterial = Material.GRAY_STAINED_GLASS_PANE;
                    }
                    item = new ItemStack(resolvedMaterial);
                } else {
                    builtFromCustomItem = true;
                }
            } else {
                    Material resolvedMaterial = material;
                    if (resolvedMaterial == null) {
                        resolvedMaterial = Material.GRAY_STAINED_GLASS_PANE;
                    }
                    item = new ItemStack(resolvedMaterial);
            }

            org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
            if (meta == null) {
                return item;
            }

            applyCustomModelData(meta, customModelData);

            if (!builtFromCustomItem && (name != null || nameKey != null)) {
                String processedName = applyPlaceholders(resolveText(name, nameKey), placeholders);
                meta.displayName(LEGACY.deserialize(processedName));
            }

            if ((lore != null && !lore.isEmpty()) || !loreKeys.isEmpty()) {
                List<net.kyori.adventure.text.Component> processedLore = new ArrayList<>();
                if (builtFromCustomItem && meta.lore() != null) {
                    processedLore.addAll(meta.lore());
                }
                if (lore != null) {
                    for (String line : lore) {
                        processedLore.add(LEGACY.deserialize(applyPlaceholders(line, placeholders)));
                    }
                }
                for (String key : loreKeys) {
                    processedLore.add(LEGACY.deserialize(applyPlaceholders(resolveText(null, key), placeholders)));
                }
                meta.lore(processedLore);
            }

            item.setItemMeta(meta);
            return item;
        }

        private void applyCustomModelData(ItemMeta meta, Integer customModelData) {
            if (customModelData == null) {
                return;
            }
            try {
                Method setter = meta.getClass().getMethod("setCustomModelData", Integer.class);
                setter.invoke(meta, customModelData);
            } catch (ReflectiveOperationException ignored) {
            }
        }

        private String resolveText(String fallback, String key) {
            if (key == null || key.isBlank()) {
                if (fallback != null) {
                    return fallback;
                }
                return "";
            }
            String translated = I18n.get(key);
            if (!key.equals(translated)) {
                return translated;
            }
            if (fallback != null) {
                return fallback;
            }
            return key;
        }

        private String applyPlaceholders(String text, Map<String, String> placeholders) {
        String processed = "";
        if (text != null) {
            processed = text;
        }
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                processed = processed.replace("{" + entry.getKey() + "}", entry.getValue());
            }
            return processed;
        }
    }
}
