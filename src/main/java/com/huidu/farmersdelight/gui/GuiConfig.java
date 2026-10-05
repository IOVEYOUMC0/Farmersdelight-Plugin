package com.huidu.farmersdelight.gui;

import com.huidu.farmersdelight.api.config.ConfigSectionReader;
import com.huidu.farmersdelight.api.gui.GuiLayout;
import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.CompatItemMeta;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.Text;
import net.kyori.adventure.text.Component;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Locale;

public class GuiConfig implements GuiLayout {

    private final String title;
    private final String titleLayoutOffset;
    private final String titleLayoutIcon;
    private final boolean fillersEnabled;
    private final int rows;
    private final List<String> layout;
    private final Map<Character, String> legend;
    private final Map<String, GuiItem> items;

    private final int[] ingredientSlots;
    private final int[] containerSlots;
    private final int[] bufferSlots;
    private final int[] outputSlots;
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
        List<Integer> containers = new ArrayList<>();
        List<Integer> buffers = new ArrayList<>();
        List<Integer> outputs = new ArrayList<>();
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
                        case "container" -> {
                            containers.add(slot);
                            if (container < 0) container = slot;
                        }
                        case "progress" -> progress = slot;
                        case "meal", "buffer" -> {
                            buffers.add(slot);
                            if (buffer < 0) buffer = slot;
                        }
                        case "output" -> {
                            outputs.add(slot);
                            if (output < 0) output = slot;
                        }
                        case "recipe" -> recipe = slot;
                        default -> {
                        }
                    }
                }
            }
        }

        this.ingredientSlots = ingredients.stream().mapToInt(i -> i).toArray();
        this.containerSlots = containers.stream().mapToInt(i -> i).toArray();
        this.bufferSlots = buffers.stream().mapToInt(i -> i).toArray();
        this.outputSlots = outputs.stream().mapToInt(i -> i).toArray();
        this.heatSlot = heat;
        this.containerSlot = container;
        this.progressSlot = progress;
        this.bufferSlot = buffer;
        this.outputSlot = output;
        this.recipeSlot = recipe;
    }

    public static GuiConfig fromConfig(ConfigurationSection section) {
        String title = ConfigSectionReader.optionalString(section, "title", "GUI");
        String titleLayoutOffset = ConfigSectionReader.optionalString(section, "title-layout.craftengine.offset", "");
        String titleLayoutIcon = ConfigSectionReader.optionalString(section, "title-layout.craftengine.icon", "");
        boolean fillersEnabled = ConfigSectionReader.optionalBoolean(section, "fillers-enabled", true);
        int rows = ConfigSectionReader.optionalInt(section, "rows", 3);
        List<String> layout = ConfigSectionReader.optionalStringList(section, "layout");

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
        inheritBackgroundVisualOptions(items);

        List<GuiItem> progressItems = new ArrayList<>();
        List<Map<?, ?>> progressItemsList = ConfigSectionReader.optionalMapList(section, "progress-items");
        if (progressItemsList.isEmpty() && itemsSection != null) {
            progressItemsList = itemsSection.getMapList("progress-items");
        }
        for (Map<?, ?> itemMap : progressItemsList) {
            GuiItem progressItem = GuiItem.fromMap(itemMap);
            if (progressItem != null) {
                progressItems.add(progressItem);
            }
        }

        GuiLayoutWarnings.warnUnknownLayoutCharacters(section.getCurrentPath(), "cooking-pot-gui",
                rows, layout, legend);
        return new GuiConfig(title, titleLayoutOffset, titleLayoutIcon, fillersEnabled, rows, layout, legend, items,
                progressItems);
    }

    static void inheritBackgroundVisualOptions(Map<String, GuiItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        GuiItem background = items.get("background");
        GuiItem decoration = items.get("decoration");
        if (background != null && decoration != null) {
            items.put("decoration", decoration.withMissingVisualOptionsFrom(background));
        }
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
        legend.put('X', "background");
        legend.put(' ', "background");

        Map<String, GuiItem> items = new HashMap<>();
        items.put("background", new GuiItem(Material.GRAY_STAINED_GLASS_PANE, null, " ", List.of()));
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

    @Override
    public int rows() {
        return rows;
    }

    @Override
    public int size() {
        return getSize();
    }

    /**
     * A cell is functional when the grid draws it and its legend type is not
     * GuiLayout#BACKGROUND. This is deliberately wider than
     * isInteractiveSlot(int), which answers whether a click may put contents there; display-only
     * cells (progress, output, meal) are functional here and still must not be written back.
     *
     *
     * A slot outside the inventory is not functional: the per-slot lookup below would read a negative column,
     * and a click handler asking about a slot it did not own must get false rather than an exception.
     */
    @Override
    public boolean isFunctional(int slot) {
        if (!contains(slot)) {
            return false;
        }
        String type = getSlotType(slot);
        return type != null && !GuiLayout.BACKGROUND.equals(type);
    }

    /**
     * Answered from the grid, so a type no legend character maps to returns an empty array, and cells the
     * legend maps to it are returned in ascending slot order.
     */
    @Override
    public int[] slotsOf(String type) {
        if (type == null) {
            return new int[0];
        }
        int size = getSize();
        int[] found = new int[size];
        int count = 0;
        for (int slot = 0; slot < size; slot++) {
            if (type.equals(getSlotType(slot))) {
                found[count++] = slot;
            }
        }
        return Arrays.copyOf(found, count);
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

    public int[] getContainerSlots() {
        return containerSlots;
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

    public int[] getBufferSlots() {
        return bufferSlots;
    }

    public int getOutputSlot() {
        return outputSlot;
    }

    public int[] getOutputSlots() {
        return outputSlots;
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
            return null;
        }

        String line = layout.get(row);
        if (col >= line.length()) {
            return null;
        }

        char c = line.charAt(col);
        return legend.get(c);
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
        return contains(containerSlots, slot);
    }

    public boolean isMealSlot(int slot) {
        return contains(bufferSlots, slot);
    }

    public boolean isBufferSlot(int slot) {
        return contains(bufferSlots, slot);
    }

    public boolean isOutputSlot(int slot) {
        return contains(outputSlots, slot);
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

    private static boolean contains(int[] slots, int slot) {
        for (int candidate : slots) {
            if (candidate == slot) {
                return true;
            }
        }
        return false;
    }

    public static class GuiItem {
        private final Material material;
        private final Key customItemId;
        private final Integer customModelData;
        private final String itemModel;
        private final boolean hideTooltip;
        private final String name;
        private final List<String> lore;
        private final String nameKey;
        private final List<String> loreKeys;
        private final List<String> commands;
        private volatile ItemStack cachedNoPlaceholders;
        private volatile ItemStack cachedCustomBase;
        private volatile Boolean cachedCustomBaseIsReal;

        public GuiItem(Material material, Key customItemId, String name, List<String> lore) {
            this(material, customItemId, null, name, lore);
        }

        public GuiItem(Material material, Key customItemId, Integer customModelData, String name, List<String> lore) {
            this(material, customItemId, customModelData, null, false, name, lore, null, List.of());
        }

        public GuiItem(Material material, Key customItemId, Integer customModelData, String name, List<String> lore,
                       String nameKey, List<String> loreKeys) {
            this(material, customItemId, customModelData, null, false, name, lore, nameKey, loreKeys);
        }

        public GuiItem(Material material, Key customItemId, Integer customModelData, String itemModel,
                       boolean hideTooltip, String name, List<String> lore, String nameKey, List<String> loreKeys) {
            this(material, customItemId, customModelData, itemModel, hideTooltip, name, lore, nameKey, loreKeys, List.of());
        }

        public GuiItem(Material material, Key customItemId, Integer customModelData, String itemModel,
                       boolean hideTooltip, String name, List<String> lore, String nameKey, List<String> loreKeys,
                       List<String> commands) {
            this.material = material;
            this.customItemId = customItemId;
            this.customModelData = customModelData;
            this.itemModel = itemModel;
            this.hideTooltip = hideTooltip;
            this.name = name;
            this.lore = lore;
            this.nameKey = nameKey;
            if (loreKeys != null) {
                this.loreKeys = loreKeys;
            } else {
                this.loreKeys = List.of();
            }
            if (commands != null && !commands.isEmpty()) {
                List<String> normalizedCommands = new ArrayList<>();
                for (String command : commands) {
                    if (command != null && !command.isBlank()) {
                        normalizedCommands.add(command.trim());
                    }
                }
                this.commands = List.copyOf(normalizedCommands);
            } else {
                this.commands = List.of();
            }
        }

        public static GuiItem fromConfig(ConfigurationSection section) {
            String materialName = ConfigSectionReader.optionalString(section, "material");
            Material material = null;
            if (materialName != null && !materialName.isEmpty()) {
                try {
                    material = Registry.MATERIAL.get(NamespacedKey.minecraft(materialName.toLowerCase(Locale.ROOT)));
                } catch (Exception e) {
                    material = Material.GRAY_STAINED_GLASS_PANE;
                }
                if (material == null) {
                    material = Material.GRAY_STAINED_GLASS_PANE;
                }
            }

            String customItemIdStr = ConfigSectionReader.optionalString(section, "item");
            Key customItemId = null;
            if (customItemIdStr != null && !customItemIdStr.isEmpty()) {
                customItemId = Key.of(customItemIdStr);
            }

            if (material == null && customItemId == null) {
                material = Material.GRAY_STAINED_GLASS_PANE;
            }

            String name = ConfigSectionReader.optionalString(section, "name", " ");
            List<String> lore = ConfigSectionReader.optionalStringList(section, "lore");
            String nameKey = ConfigSectionReader.optionalString(section, "name-key");
            List<String> loreKeys = ConfigSectionReader.optionalStringList(section, "lore-keys");
            Integer customModelData = ConfigSectionReader.optionalIntOrNull(section, "custom-model-data", "customModelData");
            String itemModel = ConfigSectionReader.optionalString(section, "item-model", null, "item_model");
            boolean hideTooltip = ConfigSectionReader.optionalBoolean(section, "hide-tooltip", false,
                    "hide_tooltip", "hideTooltip");
            List<String> commands = readCommands(section);

            return new GuiItem(material, customItemId, customModelData, itemModel, hideTooltip,
                    name, lore, nameKey, loreKeys, commands);
        }

        public static GuiItem fromMap(Map<?, ?> map) {
            if (map == null || map.isEmpty()) {
                return null;
            }

            Material material = null;
            Object materialValue = map.get("material");
            if (materialValue != null) {
                try {
                    material = Registry.MATERIAL.get(NamespacedKey.minecraft(materialValue.toString().toLowerCase(Locale.ROOT)));
                } catch (Exception ignored) {
                    material = Material.GRAY_STAINED_GLASS_PANE;
                }
                if (material == null) {
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
            String itemModel = null;
            Object itemModelValue = map.containsKey("item-model")
                    ? map.get("item-model")
                    : map.get("item_model");
            if (itemModelValue != null) {
                itemModel = itemModelValue.toString();
            }
            boolean hideTooltip = parseBoolean(map.containsKey("hide-tooltip")
                    ? map.get("hide-tooltip")
                    : (map.containsKey("hide_tooltip") ? map.get("hide_tooltip") : map.get("hideTooltip")));
            List<String> commands = readCommands(map);
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

            return new GuiItem(material, customItemId, customModelData, itemModel, hideTooltip,
                    name, lore, nameKey, loreKeys, commands);
        }

        private GuiItem withMissingVisualOptionsFrom(GuiItem fallback) {
            if (fallback == null) {
                return this;
            }
            Integer resolvedCustomModelData = this.customModelData != null ? this.customModelData : fallback.customModelData;
            String resolvedItemModel = hasText(this.itemModel) ? this.itemModel : fallback.itemModel;
            boolean resolvedHideTooltip = this.hideTooltip || fallback.hideTooltip;
            if (Objects.equals(resolvedCustomModelData, this.customModelData)
                    && Objects.equals(resolvedItemModel, this.itemModel)
                    && resolvedHideTooltip == this.hideTooltip) {
                return this;
            }
            return new GuiItem(material, customItemId, resolvedCustomModelData, resolvedItemModel, resolvedHideTooltip,
                    name, lore, nameKey, loreKeys, commands);
        }

        private static boolean hasText(String value) {
            return value != null && !value.isBlank();
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

        public List<String> getCommands() {
            return commands;
        }

        public boolean hasNoCommands() {
            return commands == null || commands.isEmpty();
        }

        public ItemStack createItem() {
            // Use an immutable empty map to avoid allocating an unused HashMap every time (per slot, per redraw).
            return createItem(Map.of());
        }

        public ItemStack createItem(Map<String, String> placeholders) {
            if (placeholders.isEmpty()) {
                ItemStack cached = cachedNoPlaceholders;
                if (cached != null) return cached.clone();
            }

            ItemStack item = resolveBaseItem();

            ItemMeta meta = item.getItemMeta();
            if (meta == null) {
                return item;
            }

            applyCustomModelData(meta, customModelData);
            applyItemModel(meta, itemModel);
            applyHideTooltip(meta, hideTooltip);

            // Prefer the configured name/lore; fall back to the base item's own (e.g. a CraftEngine custom
            // item's built-in name/lore) only when the config omits them. A configured name/lore therefore
            // overrides the custom item's.
            if (name != null || nameKey != null) {
                String processedName = applyPlaceholders(resolveText(name, nameKey), placeholders);
                meta.displayName(Text.name(processedName));
            }

            if ((lore != null && !lore.isEmpty()) || !loreKeys.isEmpty()) {
                List<Component> processedLore = new ArrayList<>();
                if (lore != null) {
                    for (String line : lore) {
                        processedLore.add(Text.lore(applyPlaceholders(line, placeholders)));
                    }
                }
                for (String key : loreKeys) {
                    processedLore.add(Text.lore(applyPlaceholders(resolveText(null, key), placeholders)));
                }
                meta.lore(processedLore);
            }

            item.setItemMeta(meta);
            if (placeholders.isEmpty()) {
                cachedNoPlaceholders = item.clone();
            }
            return item;
        }

        private ItemStack resolveBaseItem() {
            if (customItemId == null) {
                Material resolvedMaterial = material;
                if (resolvedMaterial == null) {
                    resolvedMaterial = Material.GRAY_STAINED_GLASS_PANE;
                }
                return new ItemStack(resolvedMaterial);
            }

            ItemStack base = cachedCustomBase;
            if (base == null) {
                synchronized (this) {
                    base = cachedCustomBase;
                    if (base == null) {
                        base = ItemUtils.createItem(customItemId);
                        if (base != null) {
                            cachedCustomBaseIsReal = Boolean.TRUE;
                        } else {
                            Material resolvedMaterial = material;
                            if (resolvedMaterial == null) {
                                resolvedMaterial = Material.GRAY_STAINED_GLASS_PANE;
                            }
                            base = new ItemStack(resolvedMaterial);
                            cachedCustomBaseIsReal = Boolean.FALSE;
                        }
                        cachedCustomBase = base;
                    }
                }
            }
            return base.clone();
        }

        private void applyCustomModelData(ItemMeta meta, Integer customModelData) {
            if (customModelData == null) {
                return;
            }
            meta.setCustomModelData(customModelData);
        }

        private void applyItemModel(ItemMeta meta, String itemModel) {
            if (itemModel == null || itemModel.isBlank()) {
                return;
            }
            NamespacedKey key = parseNamespacedKey(itemModel);
            if (key == null) {
                return;
            }
            CompatItemMeta.setItemModel(meta, key);
        }

        private void applyHideTooltip(ItemMeta meta, boolean hideTooltip) {
            if (!hideTooltip) {
                return;
            }
            meta.setHideTooltip(true);
        }

        private static NamespacedKey parseNamespacedKey(String value) {
            String normalized = value.trim();
            if (normalized.isEmpty()) {
                return null;
            }
            if (!normalized.contains(":")) {
                normalized = "minecraft:" + normalized;
            }
            try {
                return NamespacedKey.fromString(normalized);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }

        private static boolean parseBoolean(Object value) {
            return value != null && Boolean.parseBoolean(value.toString());
        }

        private static List<String> readCommands(ConfigurationSection section) {
            List<String> commands = new ArrayList<>();
            appendCommandValue(commands, section.get("command"));
            appendCommandValue(commands, section.get("commands"));
            appendCommandValue(commands, section.get("cmd"));
            return commands;
        }

        private static List<String> readCommands(Map<?, ?> map) {
            List<String> commands = new ArrayList<>();
            appendCommandValue(commands, map.get("command"));
            appendCommandValue(commands, map.get("commands"));
            appendCommandValue(commands, map.get("cmd"));
            return commands;
        }

        private static void appendCommandValue(List<String> commands, Object value) {
            if (value == null) {
                return;
            }
            if (value instanceof Iterable<?> iterable) {
                for (Object entry : iterable) {
                    appendCommandValue(commands, entry);
                }
                return;
            }
            String command = value.toString().trim();
            if (!command.isEmpty()) {
                commands.add(command);
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
