package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.config.ConfigSectionReader;
import com.huidu.farmersdelight.api.recipe.SpecialRecipeInfo;
import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class SpecialRecipeLoader {

    private static final String FILE_NAME = "recipes/special_recipes.yml";
    private static final String ROOT_KEY = "special_recipes";

    private SpecialRecipeLoader() {
    }

    public static void load(FarmersDelightPlugin plugin, SpecialRecipeRegistry registry) {
        YamlConfiguration config = loadConfig(plugin);
        if (config == null) return;

        registerSection(plugin, registry, config, false);
        // Backfill bundled recipes the on-disk file does not define, so servers with an older
        // special_recipes.yml still gain newly bundled entries (disk entries always win on conflict).
        registerSection(plugin, registry, loadBundled(plugin), true);
    }

    private static void registerSection(FarmersDelightPlugin plugin, SpecialRecipeRegistry registry,
                                        YamlConfiguration config, boolean backfillOnly) {
        if (config == null) return;
        ConfigurationSection root = config.getConfigurationSection(ROOT_KEY);
        if (root == null) {
            if (!backfillOnly) {
                I18n.logWarning("recipe.special_recipe_missing_root", "file", FILE_NAME);
            }
            return;
        }

        int count = 0;
        for (String recipeId : root.getKeys(false)) {
            if (backfillOnly && registry.get(recipeId) != null) {
                continue;
            }
            ConfigurationSection section = root.getConfigurationSection(recipeId);
            if (section == null) continue;

            try {
                SpecialRecipeInfo info = parseRecipe(recipeId, section);
                registry.register(info);
                count++;
            } catch (Exception e) {
                I18n.logWarning("recipe.special_recipe_parse_failed",
                        "id", recipeId, "error", e.getMessage());
            }
        }
        if (backfillOnly) {
            if (count > 0) {
                I18n.logDetail("recipe", "recipe.special_recipe_backfilled", "count", count);
            }
        } else {
            I18n.logDetail("recipe", "recipe.special_recipe_loaded", "count", count);
        }
    }

    private static YamlConfiguration loadConfig(FarmersDelightPlugin plugin) {
        File file = new File(plugin.getDataFolder(), FILE_NAME);
        if (!file.exists()) {
            // Carry over a server's old root-level special_recipes.yml (keeps player edits) before
            // falling back to releasing the bundled default under recipes/.
            File legacy = new File(plugin.getDataFolder(), "special_recipes.yml");
            if (legacy.exists() && !legacy.isDirectory()) {
                try {
                    File parent = file.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }
                    Files.copy(legacy.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    I18n.logWarning("recipe.special_recipe_save_failed",
                            "file", FILE_NAME, "error", e.getMessage());
                }
            } else {
                try {
                    plugin.saveResource(FILE_NAME, false);
                } catch (IllegalArgumentException e) {
                    I18n.logWarning("recipe.special_recipe_save_failed",
                            "file", FILE_NAME, "error", e.getMessage());
                    return null;
                }
            }
        }

        try (Reader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(file.toPath()), StandardCharsets.UTF_8), 8192)) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(reader);
            return yaml;
        } catch (Exception e) {
            I18n.logWarning("recipe.special_recipe_load_failed",
                    "file", FILE_NAME, "error", e.getMessage());
            return null;
        }
    }

    private static YamlConfiguration loadBundled(FarmersDelightPlugin plugin) {
        try (Reader reader = new BufferedReader(
                new InputStreamReader(plugin.getResource(FILE_NAME), StandardCharsets.UTF_8), 8192)) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(reader);
            return yaml;
        } catch (Exception e) {
            I18n.logWarning("recipe.special_recipe_load_failed",
                    "file", FILE_NAME, "error", e.getMessage());
            return null;
        }
    }

    /** Parses one special-recipe config entry into a SpecialRecipeInfo; exposed so addons can drive their
     *  special recipes from a config file exactly like their other recipes. */
    public static SpecialRecipeInfo parseRecipe(String id, ConfigurationSection section) {
        String titleKey = ConfigSectionReader.optionalString(section, "title", "gui.special_recipe." + id + ".title");
        String iconItemId = ConfigSectionReader.optionalString(section, "icon", "minecraft:barrier");
        List<String> descriptionKeys = ConfigSectionReader.optionalStringList(section, "description");
        String displayType = ConfigSectionReader.optionalString(section, "display-type", SpecialRecipeInfo.DISPLAY_RECIPE);

        List<SpecialRecipeInfo.SlotEntry> inputSlots = parseSlotEntries(section, "inputs");
        List<SpecialRecipeInfo.SlotEntry> outputSlots = parseSlotEntries(section, "outputs");

        // Conditions: sunlight, water, catalyst_info, catalysts
        ConfigurationSection conditions = section.getConfigurationSection("conditions");
        boolean hasSunlight = false;
        boolean hasWater = false;
        boolean hasCatalystInfo = false;
        List<SpecialRecipeInfo.SlotEntry> catalystSlots = List.of();

        if (conditions != null) {
            hasSunlight = ConfigSectionReader.optionalBoolean(conditions, "sunlight", false);
            hasWater = ConfigSectionReader.optionalBoolean(conditions, "water", false);
            hasCatalystInfo = ConfigSectionReader.optionalBoolean(conditions, "catalyst_info", false);
        }

        // Top-level catalysts list (for recipes without a conditions block)
        catalystSlots = parseSlotEntries(section, "catalysts");
        if (catalystSlots.isEmpty() && conditions != null) {
            catalystSlots = parseSlotEntries(conditions, "catalysts");
        }

        return new SpecialRecipeInfo(id, titleKey, iconItemId,
                descriptionKeys, inputSlots, outputSlots,
                hasSunlight, hasWater, hasCatalystInfo, catalystSlots, displayType);
    }

    private static List<SpecialRecipeInfo.SlotEntry> parseSlotEntries(ConfigurationSection parent, String key) {
        List<SpecialRecipeInfo.SlotEntry> entries = new ArrayList<>();
        // Bukkit hands a YAML list-of-maps back as Map elements, not ConfigurationSection, so read it as
        // a map list and wrap each entry in a section before parsing its fields.
        for (Map<?, ?> raw : parent.getMapList(key)) {
            SpecialRecipeInfo.SlotEntry entry = parseSlotEntry(new MemoryConfiguration().createSection("entry", raw));
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    private static SpecialRecipeInfo.SlotEntry parseSlotEntry(ConfigurationSection parent, String key) {
        ConfigurationSection section = parent.getConfigurationSection(key);
        if (section == null) return null;
        return parseSlotEntry(section);
    }

    private static SpecialRecipeInfo.SlotEntry parseSlotEntry(ConfigurationSection section) {
        String itemId = ConfigSectionReader.optionalString(section, "item");
        // Behavior-list reference: "behavior: <block id>" + "list: <config key>" (e.g. the
        // organic_compost behavior's "activators"). Resolved lazily at display time.
        String behaviorBlockId = ConfigSectionReader.optionalString(section, "behavior");
        String behaviorListKey = ConfigSectionReader.optionalString(section, "list");
        if (itemId == null && behaviorBlockId == null) return null;
        String nameKey = ConfigSectionReader.optionalString(section, "name", "");
        List<String> loreKeys = ConfigSectionReader.optionalStringList(section, "lore");
        return new SpecialRecipeInfo.SlotEntry(itemId, behaviorBlockId, behaviorListKey, nameKey, loreKeys);
    }
}