package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.function.BiConsumer;

final class RecipeFileLoader {

    private RecipeFileLoader() {
    }

    static void loadRecipeSections(FarmersDelightPlugin plugin,
                                   String relativePath,
                                   String rootSectionKey,
                                   String recipeTypeName,
                                   BiConsumer<String, ConfigurationSection> sectionConsumer) {
        File recipesFile = new File(plugin.getDataFolder(), relativePath);
        if (!recipesFile.exists()) {
            plugin.saveResource(relativePath, false);
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(recipesFile);
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

