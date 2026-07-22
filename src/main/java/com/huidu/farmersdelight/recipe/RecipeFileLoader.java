package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.config.ConfigFileUpdater;
import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

final class RecipeFileLoader {

    /**
     * Opt-in switch in config.yml. saveResource only writes a recipe file that is missing entirely, so
     * recipes added by a newer plugin version never reach a server that already has the file. Merging them
     * in cannot be the default: an admin disables a recipe by deleting it, and a blanket merge would bring
     * every deleted recipe back.
     */
    private static final String MERGE_MISSING_SETTING = "recipes.merge-missing-bundled";

    /** Recipe files whose missing-id summary has already been logged, so the report is one line per file per startup. */
    private static final Set<String> REPORTED_MISSING_FILES = ConcurrentHashMap.newKeySet();

    /** Upper bound on the ids spelled out in the summary line, so a heavily trimmed file cannot flood the console. */
    private static final int MAX_REPORTED_IDS = 20;

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
        return loadRecipeFile(plugin, relativePath, true);
    }

    /**
     * @param reconcileWithBundled when true, recipe ids present in the jar but absent from the file on disk
     *                             are reported (and merged in when the admin opted in). Pass false for the
     *                             in-game recipe editor: it loads the file only to write it straight back,
     *                             and a merge there would re-add the very entry an admin just deleted.
     */
    static YamlConfiguration loadRecipeFile(FarmersDelightPlugin plugin, String relativePath, boolean reconcileWithBundled) {
        File recipesFile = new File(plugin.getDataFolder(), relativePath);
        if (!recipesFile.exists()) {
            try {
                plugin.saveResource(relativePath, false);
            } catch (IllegalArgumentException e) {
                I18n.logWarning("plugin.recipe_bundled_save_failed", "file", relativePath, "error", e.getMessage());
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
            // Only when the file parsed: on the failure path below the configuration is empty, and every
            // bundled recipe would look missing.
            if (reconcileWithBundled) {
                reconcileWithBundledRecipes(plugin, relativePath, yaml);
            }
            return yaml;
        } catch (Exception e) {
            I18n.logWarning("plugin.recipe_load_failed", "file", relativePath, "error", e.getMessage());
            return new YamlConfiguration();
        }
    }

    /**
     * Compares the recipe ids in the jar's copy of the file against the ids on disk. By default the ids the
     * admin's file does not have are only logged, because a missing id is usually a recipe that was deleted
     * on purpose to disable it. When the admin sets the opt-in setting, the missing ids are added back.
     * An id that exists on disk is never touched under either setting.
     */
    private static void reconcileWithBundledRecipes(FarmersDelightPlugin plugin, String relativePath, YamlConfiguration onDisk) {
        YamlConfiguration bundled = readBundledRecipeFile(plugin, relativePath);
        if (bundled == null) {
            return;
        }

        List<String> missing = new ArrayList<>();
        for (String path : bundled.getKeys(true)) {
            if (isRecipeEntry(bundled, path) && !onDisk.isSet(path)) {
                missing.add(path);
            }
        }
        if (missing.isEmpty()) {
            return;
        }

        if (!plugin.getConfig().getBoolean(MERGE_MISSING_SETTING, false)) {
            if (REPORTED_MISSING_FILES.add(relativePath)) {
                I18n.logInfo("plugin.recipe_bundled_missing",
                        "file", relativePath,
                        "count", missing.size(),
                        "ids", summarizeIds(missing),
                        "setting", MERGE_MISSING_SETTING);
            }
            return;
        }

        for (String path : missing) {
            ConfigurationSection body = bundled.getConfigurationSection(path);
            if (body != null) {
                onDisk.createSection(path, body.getValues(false));
            }
        }
        try {
            backupRecipeFile(plugin, relativePath);
            ConfigFileUpdater.tidy(onDisk);
            writeRecipeFile(plugin, relativePath, onDisk.saveToString());
            I18n.logInfo("plugin.recipe_bundled_merged",
                    "file", relativePath,
                    "count", missing.size(),
                    "ids", summarizeIds(missing));
        } catch (IOException e) {
            I18n.logWarning("plugin.recipe_bundled_merge_failed", "file", relativePath, "error", e.getMessage());
        }
    }

    /** Keeps the summary readable when a file is missing a large number of ids: the count is always exact. */
    private static String summarizeIds(List<String> ids) {
        if (ids.size() <= MAX_REPORTED_IDS) {
            return String.join(", ", ids);
        }
        return String.join(", ", ids.subList(0, MAX_REPORTED_IDS)) + ", ...";
    }

    /**
     * A recipe entry is a section whose own children are all plain values: the recipe body. That skips the
     * root sections (cooking_pot_recipes, cutting_board_recipes) and the group level of
     * custom_cooking_pot_recipes, whose children are sections themselves.
     */
    private static boolean isRecipeEntry(ConfigurationSection root, String path) {
        ConfigurationSection section = root.getConfigurationSection(path);
        if (section == null) {
            return false;
        }
        for (String child : section.getKeys(false)) {
            if (section.isConfigurationSection(child)) {
                return false;
            }
        }
        return true;
    }

    private static YamlConfiguration readBundledRecipeFile(FarmersDelightPlugin plugin, String relativePath) {
        try (InputStream stream = plugin.getResource(relativePath)) {
            if (stream == null) {
                return null;
            }
            YamlConfiguration bundled = new YamlConfiguration();
            try (Reader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8), 8192)) {
                bundled.load(reader);
            }
            return bundled;
        } catch (Exception e) {
            I18n.logWarning("plugin.recipe_bundled_merge_failed", "file", relativePath, "error", e.getMessage());
            return null;
        }
    }

    private static void backupRecipeFile(FarmersDelightPlugin plugin, String relativePath) throws IOException {
        Path target = new File(plugin.getDataFolder(), relativePath).toPath();
        if (Files.notExists(target)) {
            return;
        }
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path backup = target.resolveSibling(target.getFileName() + "." + timestamp + ".bak");
        Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void writeRecipeFile(FarmersDelightPlugin plugin, String relativePath, String content) throws IOException {
        Path target = new File(plugin.getDataFolder(), relativePath).toPath();
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
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

        I18n.logDetail("recipe", "recipe.loaded_total", "count", loadedCount, "type", recipeTypeName);
    }
}
