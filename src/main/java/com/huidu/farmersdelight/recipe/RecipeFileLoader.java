package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.config.ConfigFileUpdater;
import com.huidu.farmersdelight.api.config.ConfigSectionReader;
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

public final class RecipeFileLoader {

    // Read by SpecialRecipeLoader as well: the same switch controls whether bundled entries missing from an
    // operator file are merged back, for recipes and for special-recipe cards alike.
    static final String MERGE_MISSING_SETTING = "recipes.merge-missing-bundled";

    // CE/Folia readiness can invoke recipe loading twice during startup. Keep identical diagnostics
    // from flooding the console; a changed path/detail still produces a fresh warning.
    private static final Set<String> REPORTED_ISSUES = ConcurrentHashMap.newKeySet();

    private static final int MAX_REPORTED_IDS = 20;

    private RecipeFileLoader() {
    }

    /** Starts a new operator-triggered recipe reload diagnostic cycle. */
    public static void resetReportedIssues() {
        REPORTED_ISSUES.clear();
    }

    /** Problems reported since the last resetReportedIssues(), for the reload command's summary. */
    public static int reportedIssueCount() {
        return REPORTED_ISSUES.size();
    }

    static YamlConfiguration loadRecipeFile(FarmersDelightPlugin plugin, String relativePath) {
        return loadRecipeFile(plugin, relativePath, true);
    }

    // Returns null when the file could not be obtained or parsed. Callers must then keep the set they
    // published last: an unreadable file parsed as an empty configuration would drop every bundled recipe
    // on the next reload, which is exactly what a single indentation mistake used to do.
    public static YamlConfiguration loadRecipeFile(FarmersDelightPlugin plugin, String relativePath, boolean reconcileWithBundled) {
        File recipesFile = new File(plugin.getDataFolder(), relativePath);
        if (!recipesFile.exists()) {
            try {
                plugin.saveResource(relativePath, false);
            } catch (IllegalArgumentException e) {
                I18n.logWarning("plugin.recipe_bundled_save_failed", "file", relativePath, "error", e.getMessage());
                return null;
            }
        }

        // Change detection: a reload that finds this file at the same stamp as the last parse reuses that
        // parse instead of reading and parsing it again. The stamp is taken after saveResource below (it may
        // have just created the file) and refreshed after reconciliation (it may have written it).
        RecipeFileStamps.Stamp stamp = RecipeFileStamps.Stamp.of(recipesFile);
        YamlConfiguration unchanged = RecipeFileStamps.cached(relativePath, stamp);
        if (unchanged != null) {
            return unchanged;
        }

        // Read explicitly as UTF-8 (consistent with config.yml / language files), rather than the deprecated
        // loadConfiguration(File) that uses the platform default charset, so non-ASCII recipe content is not
        // corrupted on servers whose default charset is not UTF-8 (common on Windows).
        // Buffer the stream: yaml.load() issues many small read() calls; without buffering each call
        // crosses into the OS/file-system layer (and on reload paths this runs on the main thread).
        try (Reader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(recipesFile.toPath()), StandardCharsets.UTF_8), 8192)) {
            RecipeFileStamps.noteParse();
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(reader);
            // Only when the file parsed: on the failure path below the configuration is empty, and every
            // bundled recipe would look missing.
            if (reconcileWithBundled) {
                reconcileWithBundledRecipes(plugin, relativePath, yaml);
            }
            // Re-stamp: reconciliation may have appended missing bundled entries and rewritten the file.
            RecipeFileStamps.remember(relativePath, RecipeFileStamps.Stamp.of(recipesFile), yaml);
            return yaml;
        } catch (Exception e) {
            I18n.logWarning("plugin.recipe_load_failed", "file", relativePath, "error", e.getMessage());
            return null;
        }
    }

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

        if (!ConfigSectionReader.optionalBoolean(plugin.getConfig(), MERGE_MISSING_SETTING, false)) {
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

    private static String summarizeIds(List<String> ids) {
        if (ids.size() <= MAX_REPORTED_IDS) {
            return String.join(", ", ids);
        }
        return String.join(", ", ids.subList(0, MAX_REPORTED_IDS)) + ", ...";
    }

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

    /**
     * Prepares one recipe file (or pack section) as a source of the caller's registration round: the segment
     * it returns carries that file's entries, the per-entry work, and the reporting that waits until the file
     * went through. Returns null when there is nothing to register, so the caller can queue the rest.
     */
    static RecipeRegistrationRound.Segment recipeSectionSegment(FarmersDelightPlugin plugin,
                                                                YamlConfiguration config,
                                                                String rootSectionKey,
                                                                String recipeTypeName,
                                                                String sourceFile,
                                                                BiConsumer<String, ConfigurationSection> sectionConsumer) {
        // A null config is an unreadable file (see loadRecipeFile): the caller keeps whatever it published
        // last, so there is nothing to parse here.
        if (config == null) {
            return null;
        }
        ConfigurationSection recipesSection = config.getConfigurationSection(rootSectionKey);
        if (recipesSection == null) {
            if (config.isSet(rootSectionKey)) {
                I18n.logWarning("plugin.recipe_issues_header", "file", sourceFile, "count", 1);
                I18n.logWarning("plugin.recipe_issue_detail", "index", 1,
                        "detail", rootSectionKey + " - expected a section");
            }
            return null;
        }

        // Registration is sharded: the first slice of the round runs inline (so a small file behaves exactly as
        // before) and the rest continues on the following ticks through the driver's task, at most the
        // configured budget each. Queuing the segment instead of starting it keeps the sources of one reload in
        // one round.
        Pass pass = new Pass(plugin, recipesSection, recipeTypeName, sourceFile, sectionConsumer);
        List<String> ids = new ArrayList<>(recipesSection.getKeys(false));
        return new RecipeRegistrationRound.Segment(ids, pass::step, pass::finish);
    }

    /** One recipe file's entries: the per-entry work and the reporting that has to wait until they went through. */
    private static final class Pass {

        private final FarmersDelightPlugin plugin;
        private final ConfigurationSection recipesSection;
        private final String recipeTypeName;
        private final String sourceFile;
        private final BiConsumer<String, ConfigurationSection> sectionConsumer;
        private final List<String> issues = new ArrayList<>();
        private int loadedCount;

        private Pass(FarmersDelightPlugin plugin, ConfigurationSection recipesSection, String recipeTypeName,
                     String sourceFile, BiConsumer<String, ConfigurationSection> sectionConsumer) {
            this.plugin = plugin;
            this.recipesSection = recipesSection;
            this.recipeTypeName = recipeTypeName;
            this.sourceFile = sourceFile;
            this.sectionConsumer = sectionConsumer;
        }

        /** The loop body, unchanged: one bad recipe is collected as an issue, never thrown out of the pass. */
        private void step(String recipeId) {
            ConfigurationSection section = recipesSection.getConfigurationSection(recipeId);
            if (section == null) {
                issues.add(recipeId + " - expected a recipe section");
                return;
            }

            try {
                sectionConsumer.accept(recipeId, section);
                loadedCount++;
                if (plugin.isDebugEnabled()) {
                    I18n.logInfo("recipe.loaded_single", "type", recipeTypeName, "id", recipeId);
                }
            } catch (Exception e) {
                issues.add(section.getCurrentPath() + " - " + errorMessage(e));
            }
        }

        /** The tail, run once the last entry of this file went through. */
        private void finish() {
            if (!issues.isEmpty()) {
                List<String> freshIssues = new ArrayList<>(issues.size());
                for (String issue : issues) {
                    if (REPORTED_ISSUES.add(sourceFile + "|" + issue)) {
                        freshIssues.add(issue);
                    }
                }
                if (!freshIssues.isEmpty()) {
                    I18n.logWarning("plugin.recipe_issues_header", "file", sourceFile, "count", freshIssues.size());
                    for (int i = 0; i < freshIssues.size(); i++) {
                        I18n.logWarning("plugin.recipe_issue_detail", "index", i + 1, "detail", freshIssues.get(i));
                    }
                }
            }

            I18n.logDetail("recipe", "recipe.loaded_total", "count", loadedCount, "type", recipeTypeName);
        }
    }

    private static String errorMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
