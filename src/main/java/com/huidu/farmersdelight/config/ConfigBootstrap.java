package com.huidu.farmersdelight.config;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.config.ConfigFileUpdater;
import com.huidu.farmersdelight.api.config.ConfigKeyRename;
import com.huidu.farmersdelight.api.config.ConfigUpdatePolicy;
import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

public final class ConfigBootstrap {

    private static final String WORLD_DATA_FILE = "world-data.yml";
    private static final String DROPS_FILE = "drops.yml";

    private static final ConfigUpdatePolicy CONFIG_POLICY = ConfigUpdatePolicy.builder()
            .migrate("knife-drops", "mob-extra-drops")
            .migrate("entity-extra-drops", "mob-extra-drops")
            .migrate("knife-drop-tools", "mob-extra-drop-tools")
            .migrate("entity-extra-drop-tools", "mob-extra-drop-tools")
            // Group top-level drop keys before moving the complete drops section to drops.yml.
            .migrate("knife-config", "drops.knife-items")
            .migrate("mob-extra-drop-tools", "drops.mob-extra-tools")
            .migrate("mob-extra-drops", "drops.mob-extra")
            .migrate("straw-drops", "drops.straw")
            // The knife list defines what counts as a knife for the cutting board, the skillet, mushroom
            // colonies, rice harvesting, the recipe viewer and addon drop rules, not only for drops.
            .migrate("drops.knife-items", "knife-items")
            // Settings several stations consume return to the top level: the experience reward and the
            // container returns are also read by addon stations through the public API.
            .migrate("cooking-pot.experience-reward", "experience-reward")
            .migrate("cooking-pot.container-returns", "container-returns")
            // Recipe discovery joins the other recipe settings.
            .migrate("recipe-discovery", "recipes.discovery")
            // The four buff sections group under one buff parent.
            .migrate("buff-persistence", "buff.persistence")
            .migrate("bossbar", "buff.display")
            .migrate("comfort-foods", "buff.comfort")
            .migrate("nourishment-foods", "buff.nourishment")
            // Comfort and Nourishment report themselves through the buff bossbar, which replaced the chat
            // reminder that was sent this many ticks before the effect ran out.
            .retire("buff.comfort.fade-warning-ticks",
                    "comfort-foods.fade-warning-ticks",
                    "buff.nourishment.fade-warning-ticks",
                    "nourishment-foods.fade-warning-ticks",
                    "tray",
                    "cooking-pot.tray",
                    "handle",
                    "cooking-pot.handle")
            .registrySection("heat-sources",
                    // Guarded at the parent, not at the foods child: an admin who disables every food by
                    // deleting the whole foods block leaves no foods path for a narrower guard to match, and
                    // the merge would write the bundled entries back one by one. The cost is that a genuinely
                    // new setting added under these two parents in a later version is not merged into an
                    // existing file.
                    "buff.comfort",
                    "comfort-foods",
                    "buff.nourishment",
                    "nourishment-foods",
                    "container-returns",
                    "cooking-pot.container-returns",
                    // Keyed by item id, so a deleted entry is a deliberate opt-out of that item's display
                    // override.
                    "cutting-board.display-overrides",
                    "cutting-board.display-tag-overrides")
            .build();

    private static final List<String> WORLD_DATA_REGISTRY_SECTIONS = List.of(
            "trades.villager",
            "trades.wandering-trader");
    private static final List<String> DROPS_REGISTRY_SECTIONS = List.of(
            "mob-extra-tools",
            "mob-extra",
            "straw");

    private final FarmersDelightPlugin plugin;

    public ConfigBootstrap(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    public void ensureConfigDefaults() {
        Path dataFolder = plugin.getDataFolder().toPath();
        Path configPath = dataFolder.resolve("config.yml");
        Path guiPath = dataFolder.resolve("gui.yml");
        Path worldDataPath = dataFolder.resolve(WORLD_DATA_FILE);
        Path dropsPath = dataFolder.resolve(DROPS_FILE);
        try {
            Files.createDirectories(dataFolder);
            if (Files.notExists(configPath)) {
                writeBundledConfig(configPath);
            }
            writeBundledResourceIfMissing(guiPath);
            writeBundledResourceIfMissing(worldDataPath, WORLD_DATA_FILE);
            writeBundledResourceIfMissing(dropsPath, DROPS_FILE);

            if (ConfigFileUpdater.needsRestore(configPath)) {
                ConfigFileUpdater.backup(configPath);
                writeBundledConfig(configPath);
                I18n.logWarning("plugin.config_restored_unreadable", "file", "config.yml");
            }
            if (ConfigFileUpdater.needsRestore(guiPath)) {
                ConfigFileUpdater.backup(guiPath);
                ConfigFileUpdater.installBundledResource(plugin, "gui.yml", guiPath, true);
                I18n.logWarning("plugin.config_restored_unreadable", "file", "gui.yml");
            }
            if (ConfigFileUpdater.needsRestore(worldDataPath)) {
                ConfigFileUpdater.backup(worldDataPath);
                ConfigFileUpdater.installBundledResource(plugin, WORLD_DATA_FILE, worldDataPath, true);
                I18n.logWarning("plugin.config_restored_unreadable", "file", WORLD_DATA_FILE);
            }
            if (ConfigFileUpdater.needsRestore(dropsPath)) {
                ConfigFileUpdater.backup(dropsPath);
                ConfigFileUpdater.installBundledResource(plugin, DROPS_FILE, dropsPath, true);
                I18n.logWarning("plugin.config_restored_unreadable", "file", DROPS_FILE);
            }
        } catch (IOException e) {
            I18n.logWarning("plugin.config_prepare_failed", "error", e.getMessage());
        }
    }

    /** Validates only keys present in the operator file against the bundled value type. */
    public void validateConfigTypes() {
        validateFile("config.yml", plugin.getConfig(), readBundledYaml("config.yml"), CONFIG_POLICY.registrySections());
        validateExternalTypes(WORLD_DATA_FILE, WORLD_DATA_REGISTRY_SECTIONS);
        validateExternalTypes(DROPS_FILE, DROPS_REGISTRY_SECTIONS);
        Path guiPath = plugin.getDataFolder().toPath().resolve("gui.yml");
        if (Files.exists(guiPath)) {
            try {
                validateFile("gui.yml", ConfigFileUpdater.readYamlFile(guiPath), readBundledYaml("gui.yml"), List.of());
            } catch (Exception e) {
                I18n.logWarning("plugin.config_load_failed", "file", "gui.yml", "error", e.getMessage());
            }
        }
    }

    private void validateExternalTypes(String fileName, List<String> registrySections) {
        Path path = plugin.getDataFolder().toPath().resolve(fileName);
        if (!Files.exists(path)) {
            return;
        }
        try {
            validateFile(fileName, ConfigFileUpdater.readYamlFile(path), readBundledYaml(fileName), registrySections);
        } catch (Exception e) {
            I18n.logWarning("plugin.config_load_failed", "file", fileName, "error", e.getMessage());
        }
    }

    private void validateFile(String fileName, ConfigurationSection existing, YamlConfiguration bundled,
                               List<String> registrySections) {
        if (existing == null || bundled == null) {
            return;
        }
        List<String> issues = new ArrayList<>();
        Set<String> registry = new HashSet<>(registrySections);
        for (String path : bundled.getKeys(true)) {
            if (isUnderRegistry(path, registry)) {
                continue;
            }
            if (bundled.isConfigurationSection(path)) {
                if (existing.isSet(path) && !existing.isConfigurationSection(path)) {
                    issues.add(path + " - " + String.valueOf(existing.get(path)) + " (expected section)");
                }
                continue;
            }
            if (!existing.contains(path, true)) {
                continue;
            }
            Object expected = bundled.get(path);
            Object actual = existing.get(path);
            if (expected == null || compatibleType(expected, actual)) {
                continue;
            }
            issues.add(path + " - " + String.valueOf(actual) + " (expected " + typeName(expected) + ")");
        }
        if (!issues.isEmpty()) {
            I18n.logWarning("plugin.config_issues_header", "file", fileName, "count", issues.size());
            for (int i = 0; i < issues.size(); i++) {
                I18n.logWarning("plugin.config_issue_detail", "index", i + 1, "detail", issues.get(i));
            }
        }
    }

    private static boolean isUnderRegistry(String path, Set<String> registrySections) {
        for (String section : registrySections) {
            if (path.startsWith(section + ".")) {
                return true;
            }
        }
        return false;
    }

    private static boolean compatibleType(Object expected, Object actual) {
        if (actual == null) {
            return false;
        }
        if (expected instanceof Number) {
            return actual instanceof Number || actual instanceof String && isNumeric((String) actual);
        }
        if (expected instanceof Boolean) {
            return actual instanceof Boolean || actual instanceof String s &&
                    (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false"));
        }
        if (expected instanceof List<?>) {
            return actual instanceof List<?>;
        }
        if (expected instanceof Map<?, ?> || expected instanceof ConfigurationSection) {
            return actual instanceof ConfigurationSection || actual instanceof Map<?, ?>;
        }
        return actual instanceof String || actual instanceof Number || actual instanceof Boolean;
    }

    private static boolean isNumeric(String value) {
        try {
            Double.parseDouble(value.trim().replace("_", ""));
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static String typeName(Object value) {
        if (value instanceof Number) return "number";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof List<?>) return "list";
        if (value instanceof Map<?, ?> || value instanceof ConfigurationSection) return "section";
        return "string";
    }

    public void migrateConfigKeys() {
        boolean changed = migrateLegacyWorldData() | migrateLegacyEnchantmentGroups();
        List<ConfigKeyRename> migrated =
                ConfigFileUpdater.applyMigrations(plugin.getConfig(), CONFIG_POLICY.migrations());
        for (ConfigKeyRename rename : migrated) {
            I18n.logInfo("plugin.config_key_migrated", "old", rename.oldPath(), "new", rename.newPath());
            changed = true;
        }
        changed |= migrateLegacyDrops();
        changed |= removeRetiredConfigKeys();
        int addedKeys = mergeMissingConfigKeys();
        if (changed || addedKeys > 0) {
            ConfigFileUpdater.tidy(plugin.getConfig());
            plugin.saveConfig();
            plugin.reloadConfig();
        }
        mergeMissingGuiKeys();
    }

    public YamlConfiguration loadWorldDataConfig() {
        return loadExternalConfig(WORLD_DATA_FILE, WORLD_DATA_REGISTRY_SECTIONS);
    }

    public YamlConfiguration loadDropsConfig() {
        return loadExternalConfig(DROPS_FILE, DROPS_REGISTRY_SECTIONS);
    }

    private YamlConfiguration loadExternalConfig(String fileName, List<String> registrySections) {
        Path configPath = plugin.getDataFolder().toPath().resolve(fileName);
        try {
            YamlConfiguration existing = ConfigFileUpdater.readYamlFile(configPath);
            YamlConfiguration bundled = readBundledYaml(fileName);
            if (bundled == null) {
                return existing;
            }
            int added = ConfigFileUpdater.copyMissingKeys(bundled, existing, registrySections);
            if (added > 0) {
                backupQuietly(configPath);
                ConfigFileUpdater.tidy(existing);
                ConfigFileUpdater.writeStringAtomically(configPath, existing.saveToString(), true);
                I18n.logInfo("plugin.config_keys_added", "file", fileName, "count", added);
            }
            return existing;
        } catch (Exception e) {
            I18n.logWarning("plugin.config_merge_failed", "file", fileName, "error", e.getMessage());
            return new YamlConfiguration();
        }
    }

    private boolean migrateLegacyDrops() {
        ConfigurationSection legacy = plugin.getConfig().getConfigurationSection("drops");
        if (legacy == null) {
            return false;
        }
        Path mainConfigPath = plugin.getDataFolder().toPath().resolve("config.yml");
        Path dropsPath = plugin.getDataFolder().toPath().resolve(DROPS_FILE);
        try {
            backupQuietly(mainConfigPath);
            backupQuietly(dropsPath);
            copyLegacyDrops(legacy, dropsPath);
            plugin.getConfig().set("drops", null);
            I18n.logInfo("plugin.config_key_migrated", "old", "drops", "new", DROPS_FILE);
            return true;
        } catch (Exception e) {
            I18n.logWarning("plugin.config_merge_failed", "file", DROPS_FILE, "error", e.getMessage());
            return false;
        }
    }

    static void copyLegacyDrops(ConfigurationSection legacy, Path dropsPath)
            throws IOException, InvalidConfigurationException {
        YamlConfiguration drops = ConfigFileUpdater.readYamlFile(dropsPath);
        for (String section : DROPS_REGISTRY_SECTIONS) {
            drops.set(section, null);
        }
        ConfigFileUpdater.copySection(legacy, drops);
        ConfigFileUpdater.tidy(drops);
        ConfigFileUpdater.writeStringAtomically(dropsPath, drops.saveToString(), true);
    }

    private boolean migrateLegacyWorldData() {
        ConfigurationSection legacy = plugin.getConfig().getConfigurationSection("world-data");
        if (legacy == null) {
            return false;
        }
        Path mainConfigPath = plugin.getDataFolder().toPath().resolve("config.yml");
        Path worldDataPath = plugin.getDataFolder().toPath().resolve(WORLD_DATA_FILE);
        try {
            backupQuietly(mainConfigPath);
            backupQuietly(worldDataPath);
            copyLegacyWorldData(legacy, worldDataPath);
            plugin.getConfig().set("world-data", null);
            I18n.logInfo("plugin.config_key_migrated", "old", "world-data", "new", WORLD_DATA_FILE);
            return true;
        } catch (Exception e) {
            I18n.logWarning("plugin.config_merge_failed", "file", WORLD_DATA_FILE, "error", e.getMessage());
            return false;
        }
    }

    static void copyLegacyWorldData(ConfigurationSection legacy, Path worldDataPath)
            throws IOException, InvalidConfigurationException {
        YamlConfiguration worldData = ConfigFileUpdater.readYamlFile(worldDataPath);
        worldData.set("trades", null);
        ConfigFileUpdater.copySection(legacy, worldData);
        ConfigFileUpdater.tidy(worldData);
        ConfigFileUpdater.writeStringAtomically(worldDataPath, worldData.saveToString(), true);
    }

    private boolean migrateLegacyEnchantmentGroups() {
        boolean tableMigrated = fanOutLegacyEnchantmentPath("table");
        boolean anvilMigrated = fanOutLegacyEnchantmentPath("anvil");
        return tableMigrated || anvilMigrated;
    }

    private boolean fanOutLegacyEnchantmentPath(String childPath) {
        String sourcePath = "enchantments." + childPath;
        if (!plugin.getConfig().isSet(sourcePath)) {
            return false;
        }
        for (String group : List.of("knives", "skillet")) {
            String targetPath = "enchantments.groups." + group + "." + childPath;
            if (ConfigFileUpdater.copyPathIfMissing(plugin.getConfig(), sourcePath, targetPath)) {
                I18n.logInfo("plugin.config_key_migrated", "old", sourcePath, "new", targetPath);
            }
        }
        plugin.getConfig().set(sourcePath, null);
        return true;
    }

    private int mergeMissingConfigKeys() {
        YamlConfiguration bundled = readBundledYaml("config.yml");
        if (bundled == null) {
            return 0;
        }
        try {
            int added = ConfigFileUpdater.copyMissingKeys(bundled, plugin.getConfig(),
                    CONFIG_POLICY.registrySections());
            if (added > 0) {
                backupQuietly(plugin.getDataFolder().toPath().resolve("config.yml"));
                I18n.logInfo("plugin.config_keys_added", "file", "config.yml", "count", added);
            }
            return added;
        } catch (Exception e) {
            I18n.logWarning("plugin.config_merge_failed", "file", "config.yml", "error", e.getMessage());
            return 0;
        }
    }

    private void mergeMissingGuiKeys() {
        Path guiPath = plugin.getDataFolder().toPath().resolve("gui.yml");
        if (Files.notExists(guiPath)) {
            return;
        }
        YamlConfiguration bundled = readBundledYaml("gui.yml");
        if (bundled == null) {
            return;
        }
        try {
            YamlConfiguration existing = ConfigFileUpdater.readYamlFile(guiPath);

            int migrated = migrateLegacyGuiSections(existing.getConfigurationSection("recipe-view-gui"));
            migrated += migrateEmptyGuiMaps(existing);
            int added = ConfigFileUpdater.copyMissingKeys(bundled, existing, List.of());
            if (migrated > 0 || added > 0) {
                backupQuietly(guiPath);
                ConfigFileUpdater.tidy(existing);
                ConfigFileUpdater.writeStringAtomically(guiPath, existing.saveToString(), true);
                I18n.logInfo("plugin.config_keys_added", "file", "gui.yml", "count", migrated + added);
            }
        } catch (Exception e) {
            I18n.logWarning("plugin.config_merge_failed", "file", "gui.yml", "error", e.getMessage());
        }
    }

    /** Repair the empty lists shipped in place of per-item GUI maps, without replacing operator entries. */
    static int migrateEmptyGuiMaps(ConfigurationSection gui) {
        int migrated = 0;
        for (String path : List.of("cooking-pot-guis", "recipe-view-gui.recipe-detail-cooking-pot-guis",
                "recipe-editor-cooking-pot-guis")) {
            if (gui.get(path) instanceof List<?> entries && entries.isEmpty()) {
                gui.createSection(path);
                migrated++;
            }
        }
        return migrated;
    }

    /** Copies the pre-split recipe detail layout into any newly introduced detail sections. */
    static int migrateLegacyGuiSections(ConfigurationSection recipeView) {
        if (recipeView == null) {
            return 0;
        }
        ConfigurationSection legacy = recipeView.getConfigurationSection("recipe-detail");
        if (legacy == null) {
            return 0;
        }
        int migrated = 0;
        for (String target : List.of("recipe-detail-cooking-pot", "recipe-detail-cutting-board")) {
            if (recipeView.getConfigurationSection(target) != null || recipeView.isSet(target)) {
                continue;
            }
            ConfigFileUpdater.copySection(legacy, recipeView.createSection(target));
            migrated++;
        }
        return migrated;
    }

    private YamlConfiguration readBundledYaml(String resourcePath) {
        try {
            return ConfigFileUpdater.readBundledYaml(plugin, resourcePath);
        } catch (Exception e) {
            I18n.logWarning("plugin.config_merge_failed", "file", resourcePath, "error", e.getMessage());
            return null;
        }
    }

    private boolean removeRetiredConfigKeys() {
        List<String> removed = ConfigFileUpdater.removeKeys(plugin.getConfig(), CONFIG_POLICY.retiredKeys());
        if (removed.isEmpty()) {
            return false;
        }
        // This path discards values the admin wrote, so it takes the same copy the merge path does. The log names
        // the keys but not what they held, and without the copy the tuned value is unrecoverable.
        backupQuietly(plugin.getDataFolder().toPath().resolve("config.yml"));
        I18n.logInfo("plugin.config_keys_retired", "count", removed.size(), "keys", String.join(", ", removed));
        return true;
    }

    private void backupQuietly(Path configPath) {
        try {
            ConfigFileUpdater.backup(configPath);
        } catch (IOException e) {
            I18n.logWarning("plugin.config_backup_failed", "file", configPath.getFileName().toString(),
                    "error", e.getMessage());
        }
    }

    private void writeBundledConfig(Path configPath) throws IOException {
        ConfigFileUpdater.installBundledResource(plugin, "config.yml", configPath, true);
    }

    private void writeBundledResourceIfMissing(Path targetPath) throws IOException {
        writeBundledResourceIfMissing(targetPath, "gui.yml");
    }

    private void writeBundledResourceIfMissing(Path targetPath, String resourcePath) throws IOException {
        if (Files.notExists(targetPath)) {
            ConfigFileUpdater.installBundledResource(plugin, resourcePath, targetPath, false);
        }
    }
}
