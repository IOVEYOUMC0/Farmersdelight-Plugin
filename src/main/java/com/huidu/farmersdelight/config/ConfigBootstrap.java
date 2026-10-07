package com.huidu.farmersdelight.config;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.config.ConfigFileUpdater;
import com.huidu.farmersdelight.api.config.ConfigKeyRename;
import com.huidu.farmersdelight.api.config.ConfigUpdateNotices;
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
import java.util.concurrent.ConcurrentHashMap;

public final class ConfigBootstrap {

    private static final String WORLD_DATA_FILE = "world-data.yml";
    private static final String DROPS_FILE = "drops.yml";
    private static final String DISPLAY_OVERRIDES_FILE = "display-overrides.yml";

    private static final ConfigUpdatePolicy CONFIG_POLICY = ConfigUpdatePolicy.builder()
            .migrate("knife-config", "drops.knife-items")
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
            // The per-station hopper flags sat under the same name as the master switch, one as a boolean and one
            // as a section; they are now allow-hopper.
            .migrate("cutting-board.hopper-interactions", "cutting-board.allow-hopper")
            .migrate("cooking-pot.hopper-interactions", "cooking-pot.allow-hopper")
            .migrate("skillet.hopper-interactions", "skillet.allow-hopper")
            // The performance knobs group by kind: warnings, per-tick work budgets and proxy display sync.
            .migrate("performance.warnings-enabled", "performance.warnings.enabled")
            .migrate("performance.warning-cooldown-seconds", "performance.warnings.cooldown-seconds")
            .migrate("performance.active-block-warning-threshold", "performance.warnings.active-block-threshold")
            .migrate("performance.cooking-pot-total-warning-threshold",
                    "performance.warnings.cooking-pot-total-threshold")
            .migrate("performance.cooking-pot-density-warning-threshold",
                    "performance.warnings.cooking-pot-density-threshold")
            .migrate("performance.craftengine-free-state-warning-threshold",
                    "performance.warnings.craftengine-free-state-threshold")
            .migrate("performance.proxy-item-display-view-distance", "performance.proxy-display.view-distance")
            .migrate("performance.proxy-item-display-sync-interval-ticks",
                    "performance.proxy-display.sync-interval-ticks")
            .migrate("performance.proxy-item-display-sync-batch-size",
                    "performance.proxy-display.sync-batch-size")
            .migrate("performance.reload-visual-refreshes-per-tick",
                    "performance.budgets.reload-visual-refreshes-per-tick")
            .migrate("performance.pet-tempt-tick-budget", "performance.budgets.pet-tempt-tick-budget")
            .migrate("performance.startup-chunk-loads-per-tick", "performance.budgets.startup-chunk-loads-per-tick")
            .migrate("performance.chunk-effect-packet-budget", "performance.budgets.chunk-effect-packet-budget")
            .migrate("performance.effect-tick-interval-ticks", "performance.budgets.effect-tick-interval-ticks")
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
                    "cooking-pot.handle",
                    // Display visibility/throttling now lives in CraftEngine, which diffs the display text itself,
                    // so this interval stopped being read and the key was removed from the bundled file.
                    "cooking-pot.display",
                    "cooking-pot.display.visibility-check-interval-ticks",
                    // The pot decides "placement or GUI" from the item in hand now, so the timer that used to
                    // swallow every interaction for a moment after a placement is no longer read.
                    "cooking-pot.place-interaction-cooldown-ms")
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
                    "cooking-pot.container-returns")
            .build();

    private static final List<String> WORLD_DATA_REGISTRY_SECTIONS = List.of(
            "trades.villager",
            "trades.wandering-trader");
    private static final List<String> DROPS_REGISTRY_SECTIONS = List.of(
            "straw");
    // Both display tables are keyed by item id or tag, so a deleted entry is a deliberate opt-out of that
    // entry's override and must never be restored by a merge.
    private static final List<String> DISPLAY_OVERRIDES_REGISTRY_SECTIONS = List.of(
            "items",
            "tags");

    /**
     * The four auxiliary files plus config.yml, all treated the same way: install when missing or
     * unreadable, back up before replacing, never overwrite an existing file. Only the first install differs:
     * config.yml is written through Bukkit so the plugin's own defaults stay in sync with the file.
     */
    private static final List<ManagedFile> MANAGED_FILES = List.of(
            new ManagedFile("config.yml", true),
            new ManagedFile("gui.yml", false),
            new ManagedFile(WORLD_DATA_FILE, false),
            new ManagedFile(DROPS_FILE, false),
            new ManagedFile(DISPLAY_OVERRIDES_FILE, false));

    private record ManagedFile(String fileName, boolean mainConfig) {

        void install(FarmersDelightPlugin plugin, Path target) throws IOException {
            if (mainConfig) {
                ConfigFileUpdater.installBundledResource(plugin, fileName, target, true);
                return;
            }
            // replace=true only in the restore case, where the unreadable file is intentionally overwritten;
            // for a first install an existing file must stay untouched.
            ConfigFileUpdater.installBundledResource(plugin, fileName, target, Files.exists(target));
        }
    }

    private final FarmersDelightPlugin plugin;

    public ConfigBootstrap(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    public void ensureConfigDefaults() {
        Path dataFolder = plugin.getDataFolder().toPath();
        try {
            Files.createDirectories(dataFolder);
            for (ManagedFile file : MANAGED_FILES) {
                Path path = dataFolder.resolve(file.fileName());
                if (Files.notExists(path)) {
                    file.install(plugin, path);
                }
                // A file that is unreadable (truncated, wrong encoding) is replaced with the bundled copy after
                // taking a backup, so a broken hand edit cannot take the plugin down on the next start.
                if (ConfigFileUpdater.needsRestore(path)) {
                    ConfigFileUpdater.backup(path);
                    file.install(plugin, path);
                    I18n.logWarning("plugin.config_restored_unreadable", "file", file.fileName());
                }
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
        validateExternalTypes(DISPLAY_OVERRIDES_FILE, DISPLAY_OVERRIDES_REGISTRY_SECTIONS);
        Path guiPath = plugin.getDataFolder().toPath().resolve("gui.yml");
        if (Files.exists(guiPath)) {
            try {
                validateFile("gui.yml", readUserYaml(guiPath, true), readBundledYaml("gui.yml"), List.of());
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
            validateFile(fileName, readUserYaml(path, true), readBundledYaml(fileName), registrySections);
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
        Set<String> registry = registrySections.isEmpty() ? Set.of() : new HashSet<>(registrySections);
        for (String path : bundled.getKeys(true)) {
            if (!registry.isEmpty() && isUnderRegistry(path, registry)) {
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
            validationIssues += issues.size();
            I18n.logWarning("plugin.config_issues_header", "file", fileName, "count", issues.size());
            for (int i = 0; i < issues.size(); i++) {
                I18n.logWarning("plugin.config_issue_detail", "index", i + 1, "detail", issues.get(i));
            }
        }
    }

    private static boolean isUnderRegistry(String path, Set<String> registrySections) {
        for (String section : registrySections) {
            // path is always prefixed by "section.", so the prefix test replaces a per-key string concat.
            if (path.startsWith(section)) {
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
        boolean changed = migrateLegacyWorldData() | migrateLegacyEnchantmentGroups()
                | migrateLegacyDisplayOverrides();
        List<ConfigKeyRename> migrated =
                ConfigFileUpdater.applyMigrations(plugin.getConfig(), CONFIG_POLICY.migrations());
        for (ConfigKeyRename rename : migrated) {
            ConfigUpdateNotices.log(plugin, ConfigUpdateNotices.migrated(rename));
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

    public YamlConfiguration loadDisplayOverridesConfig() {
        return loadExternalConfig(DISPLAY_OVERRIDES_FILE, DISPLAY_OVERRIDES_REGISTRY_SECTIONS);
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
            // Shares the validation pass's parse. This is the one reader that may add missing keys in place,
            // which is why the mutated instance is stored back rather than being left to diverge from the cache.
            YamlConfiguration existing = readUserYaml(configPath, true);
            YamlConfiguration bundled = readBundledYaml(fileName);
            if (bundled == null) {
                return existing;
            }
            int added = ConfigFileUpdater.copyMissingKeys(bundled, existing, registrySections);
            if (added > 0) {
                backupQuietly(configPath);
                ConfigFileUpdater.tidy(existing);
                ConfigFileUpdater.writeStringAtomically(configPath, existing.saveToString(), true);
                ConfigUpdateNotices.log(plugin, ConfigUpdateNotices.added(fileName, added));
                rememberReloadRead(configPath, existing);
            }
            return existing;
        } catch (Exception e) {
            I18n.logWarning("plugin.config_merge_failed", "file", fileName, "error", e.getMessage());
            return new YamlConfiguration();
        }
    }

    /** Keeps the reload read cache consistent with an instance a caller has just mutated in place. */
    private void rememberReloadRead(Path path, YamlConfiguration parsed) {
        if (cachingReloadReads && parsed != null) {
            reloadReads.put(path, parsed);
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
            ConfigUpdateNotices.log(plugin, ConfigUpdateNotices.migrated("drops", DROPS_FILE));
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
            ConfigUpdateNotices.log(plugin, ConfigUpdateNotices.migrated("world-data", WORLD_DATA_FILE));
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

    // The two display tables used to live under cutting-board in config.yml; they move to their own file so the
    // settings there stay readable. An operator's entries are copied over, never regenerated.
    private boolean migrateLegacyDisplayOverrides() {
        ConfigurationSection items = plugin.getConfig().getConfigurationSection("cutting-board.display-overrides");
        ConfigurationSection tags = plugin.getConfig().getConfigurationSection("cutting-board.display-tag-overrides");
        if (items == null && tags == null) {
            return false;
        }
        Path mainConfigPath = plugin.getDataFolder().toPath().resolve("config.yml");
        Path overridesPath = plugin.getDataFolder().toPath().resolve(DISPLAY_OVERRIDES_FILE);
        try {
            backupQuietly(mainConfigPath);
            backupQuietly(overridesPath);
            YamlConfiguration overrides = ConfigFileUpdater.readYamlFile(overridesPath);
            overrides.set("items", null);
            overrides.set("tags", null);
            if (items != null) {
                ConfigFileUpdater.copySection(items, overrides.createSection("items"));
            }
            if (tags != null) {
                ConfigFileUpdater.copySection(tags, overrides.createSection("tags"));
            }
            ConfigFileUpdater.tidy(overrides);
            ConfigFileUpdater.writeStringAtomically(overridesPath, overrides.saveToString(), true);
            plugin.getConfig().set("cutting-board.display-overrides", null);
            plugin.getConfig().set("cutting-board.display-tag-overrides", null);
            ConfigUpdateNotices.log(plugin, ConfigUpdateNotices.migrated("cutting-board.display-overrides",
                    DISPLAY_OVERRIDES_FILE));
            return true;
        } catch (Exception e) {
            I18n.logWarning("plugin.config_merge_failed", "file", DISPLAY_OVERRIDES_FILE, "error", e.getMessage());
            return false;
        }
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
                ConfigUpdateNotices.log(plugin, ConfigUpdateNotices.migrated(sourcePath, targetPath));
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
                ConfigUpdateNotices.log(plugin, ConfigUpdateNotices.added("config.yml", added));
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
            YamlConfiguration existing = readUserYaml(guiPath, true);

            int migrated = migrateLegacyGuiSections(existing.getConfigurationSection("recipe-view-gui"));
            migrated += migrateEmptyGuiMaps(existing);
            int added = ConfigFileUpdater.copyMissingKeys(bundled, existing, List.of());
            if (migrated > 0 || added > 0) {
                backupQuietly(guiPath);
                ConfigFileUpdater.tidy(existing);
                ConfigFileUpdater.writeStringAtomically(guiPath, existing.saveToString(), true);
                rememberReloadRead(guiPath, existing);
                ConfigUpdateNotices.log(plugin, ConfigUpdateNotices.added("gui.yml", migrated + added));
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
            return ConfigResources.yaml(plugin, resourcePath);
        } catch (IOException e) {
            I18n.logWarning("plugin.config_merge_failed", "file", resourcePath, "error", e.getMessage());
            return null;
        }
    }

    /**
     * The user-file reads within one reload, so a file is opened and parsed once instead of once per pass.
     *
     *
     * A reload reads the same four files twice: validateConfigTypes() parses them to compare against
     * the bundled types, and loadConfigs() parses them again to build runtime settings. The content
     * cannot change between the two passes of one reload, so both share the parse. Only reads that merely
     * inspect the values are memoised; a read whose result is about to be mutated and written back stays fresh.
     *
     *
     * Left empty outside a reload, so the enable path keeps its existing behaviour exactly.
     */
    private final Map<Path, YamlConfiguration> reloadReads = new ConcurrentHashMap<>();
    private volatile boolean cachingReloadReads;
    /** Problems the last validation pass reported, so the reload command can summarise them. */
    private int validationIssues;

    /** Opens the read cache for one reload. Must be called before the pass that validates and loads. */
    public void beginReload() {
        reloadReads.clear();
        validationIssues = 0;
        cachingReloadReads = true;
    }

    private YamlConfiguration readUserYaml(Path path, boolean shareable)
            throws IOException, InvalidConfigurationException {
        if (!cachingReloadReads || !shareable) {
            return ConfigFileUpdater.readYamlFile(path);
        }
        YamlConfiguration cached = reloadReads.get(path);
        if (cached != null) {
            return cached;
        }
        YamlConfiguration parsed = ConfigFileUpdater.readYamlFile(path);
        if (parsed != null) {
            reloadReads.put(path, parsed);
        }
        return parsed;
    }

    /** Parses the files a reload needs up front, so validation and the load pass share one read each. */
    public void prefetchReloadFiles() {
        for (String fileName : new String[]{"config.yml", "gui.yml", DROPS_FILE, WORLD_DATA_FILE,
                DISPLAY_OVERRIDES_FILE}) {
            Path path = plugin.getDataFolder().toPath().resolve(fileName);
            if (Files.notExists(path)) {
                continue;
            }
            try {
                readUserYaml(path, true);
            } catch (Exception e) {
                I18n.logWarning("plugin.config_load_failed", "file", fileName, "error", e.getMessage());
            }
        }
    }

    /** Type problems the last validation pass found. Reset by beginReload(). */
    public int validationIssueCount() {
        return validationIssues;
    }

    /**
     * The already-parsed gui.yml, so the GUI load pass does not re-open the file the validation pass just read.
     * Outside a reload this reads the file, preserving the previous behaviour.
     */
    public YamlConfiguration readGuiForLoad(Path guiPath) throws IOException, InvalidConfigurationException {
        return readUserYaml(guiPath, true);
    }

    private boolean removeRetiredConfigKeys() {
        List<String> removed = ConfigFileUpdater.removeKeys(plugin.getConfig(), CONFIG_POLICY.retiredKeys());
        if (removed.isEmpty()) {
            return false;
        }
        // This path discards values the admin wrote, so it takes the same copy the merge path does. The log names
        // the keys but not what they held, and without the copy the tuned value is unrecoverable.
        backupQuietly(plugin.getDataFolder().toPath().resolve("config.yml"));
        ConfigUpdateNotices.log(plugin, ConfigUpdateNotices.retired(removed));
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
}
