package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

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
                plugin.getLogger().warning("Failed to save bundled recipe file " + relativePath + ": " + e.getMessage());
                return new YamlConfiguration();
            }
        }

        // 显式以 UTF-8 读取（与 config.yml / 语言文件一致），而不是使用已弃用的、
        // 采用平台默认字符集的 loadConfiguration(File)，这样在默认字符集不是 UTF-8 的服务器
        // （在 Windows 上很常见）上，非 ASCII 的配方内容才不会被损坏。
        try (Reader reader = new InputStreamReader(Files.newInputStream(recipesFile.toPath()), StandardCharsets.UTF_8)) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(reader);
            return yaml;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load recipe file " + relativePath
                    + " as UTF-8 YAML; skipping it. " + e.getMessage());
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
