package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.function.BiConsumer;

final class RecipeFileLoader {

    private RecipeFileLoader() {
    }

    static void loadRecipeSections(FarmersDelightPlugin plugin,
                                   String relativePath,
                                   String rootSectionKey,
                                   String recipeTypeName,
                                   BiConsumer<String, ConfigurationSection> sectionConsumer) {
        loadRecipeSections(plugin, loadRecipeFile(plugin, relativePath), rootSectionKey, recipeTypeName, sectionConsumer);
    }

    static YamlConfiguration loadRecipeFile(FarmersDelightPlugin plugin, String relativePath) {
        File recipesFile = new File(plugin.getDataFolder(), relativePath);
        if (!recipesFile.exists()) {
            try {
                plugin.saveResource(relativePath, false);
            } catch (IllegalArgumentException e) {
                I18n.logWarning("recipe_bundled_save_failed", "file", relativePath, "error", e.getMessage());
                return new YamlConfiguration();
            }
        }

        // Read explicitly as UTF-8 (consistent with config.yml / language files), rather than the deprecated
        // loadConfiguration(File) that uses the platform default charset, so non-ASCII recipe content is not
        // corrupted on servers whose default charset is not UTF-8 (common on Windows).
        // Buffer the stream: yaml.load() issues many small read() calls; without buffering each call
        // crosses into the OS/file-system layer (and on reload paths this runs on the main thread).
        try (Reader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(recipesFile.toPath()), StandardCharsets.UTF_8), 8192)) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(reader);
            return yaml;
        } catch (Exception e) {
            I18n.logWarning("recipe_load_failed", "file", relativePath, "error", e.getMessage());
            return new YamlConfiguration();
        }
    }

    static void loadRecipeSections(FarmersDelightPlugin plugin,
                                   YamlConfiguration config,
                                   String rootSectionKey,
                                   String recipeTypeName,
                                   BiConsumer<String, ConfigurationSection> sectionConsumer) {
        ConfigurationSection recipesSection = config.getConfigurationSection(rootSectionKey);
        if (recipesSection == null) {
            return;
        }

        int loadedCount = 0;
        for (String recipeId : recipesSection.getKeys(false)) {
            ConfigurationSection section = recipesSection.getConfigurationSection(recipeId);
            if (section == null) {
                continue;
            }

            try {
                sectionConsumer.accept(recipeId, section);
                loadedCount++;
                if (plugin.isDebugEnabled()) {
                    I18n.logInfo("recipe.loaded_single", "type", recipeTypeName, "id", recipeId);
                }
            } catch (Exception e) {
                I18n.logWarning("recipe.load_failed", "id", recipeId, "error", e.getMessage());
            }
        }

        I18n.logInfo("recipe.loaded_total", "count", loadedCount, "type", recipeTypeName);
    }
}
