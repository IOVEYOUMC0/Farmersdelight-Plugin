package com.huidu.farmersdelight.config;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.config.ConfigFileUpdater;
import com.huidu.farmersdelight.api.config.ConfigKeyRename;
import com.huidu.farmersdelight.api.config.ConfigUpdatePolicy;
import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ConfigBootstrap {

    private static final ConfigUpdatePolicy CONFIG_POLICY = ConfigUpdatePolicy.builder()
            .migrate("knife-drops", "mob-extra-drops")
            .migrate("entity-extra-drops", "mob-extra-drops")
            .migrate("knife-drop-tools", "mob-extra-drop-tools")
            .migrate("entity-extra-drop-tools", "mob-extra-drop-tools")
            // The four drop/tool sections group under one drops parent.
            .migrate("knife-config", "drops.knife-items")
            .migrate("mob-extra-drop-tools", "drops.mob-extra-tools")
            .migrate("mob-extra-drops", "drops.mob-extra")
            .migrate("straw-drops", "drops.straw")
            // The knife list defines what counts as a knife for the cutting board, the skillet, mushroom
            // colonies, rice harvesting, the recipe viewer and addon drop rules, not only for drops.
            .migrate("drops.knife-items", "knife-items")
            // Settings several stations consume return to the top level: the tray belongs to the cooking pot
            // and the skillet, the experience reward and the container returns are also read by addon
            // stations through the public API.
            .migrate("cooking-pot.tray", "tray")
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
                    "nourishment-foods.fade-warning-ticks")
            .registrySection("drops.mob-extra",
                    "mob-extra-drops",
                    "drops.mob-extra-tools",
                    "mob-extra-drop-tools",
                    "heat-sources",
                    "drops.straw",
                    "straw-drops",
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
                    "cutting-board.display-tag-overrides",
                    // Keyed by item id: a deleted entry means that item no longer composts or no longer burns.
                    "world-data.composting.items",
                    "world-data.furnace-fuel.items",
                    // The offers are a list under a single trades key rather than a section of their own, and
                    // a list the admin emptied still counts as present. Guarding at the parent is what covers
                    // the admin who deletes the whole trades key instead, at the same cost the buff parents
                    // carry above.
                    "world-data.trades.villager",
                    "world-data.trades.wandering-trader")
            .build();

    private final FarmersDelightPlugin plugin;

    public ConfigBootstrap(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    public void ensureConfigDefaults() {
        Path dataFolder = plugin.getDataFolder().toPath();
        Path configPath = dataFolder.resolve("config.yml");
        Path guiPath = dataFolder.resolve("gui.yml");
        try {
            Files.createDirectories(dataFolder);
            if (Files.notExists(configPath)) {
                writeBundledConfig(configPath);
            }
            writeBundledResourceIfMissing(guiPath);

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
        } catch (IOException e) {
            I18n.logWarning("plugin.config_prepare_failed", "error", e.getMessage());
        }
    }

    public void migrateConfigKeys() {
        boolean changed = migrateLegacyEnchantmentGroups();
        List<ConfigKeyRename> migrated =
                ConfigFileUpdater.applyMigrations(plugin.getConfig(), CONFIG_POLICY.migrations());
        for (ConfigKeyRename rename : migrated) {
            I18n.logInfo("plugin.config_key_migrated", "old", rename.oldPath(), "new", rename.newPath());
            changed = true;
        }
        changed |= removeRetiredConfigKeys();
        int addedKeys = mergeMissingConfigKeys();
        if (changed || addedKeys > 0) {
            ConfigFileUpdater.tidy(plugin.getConfig());
            plugin.saveConfig();
            plugin.reloadConfig();
        }
        mergeMissingGuiKeys();
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

            int added = ConfigFileUpdater.copyMissingKeys(bundled, existing, CONFIG_POLICY.registrySections());
            if (added > 0) {
                backupQuietly(guiPath);
                ConfigFileUpdater.tidy(existing);
                ConfigFileUpdater.writeStringAtomically(guiPath, existing.saveToString(), true);
                I18n.logInfo("plugin.config_keys_added", "file", "gui.yml", "count", added);
            }
        } catch (Exception e) {
            I18n.logWarning("plugin.config_merge_failed", "file", "gui.yml", "error", e.getMessage());
        }
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
        if (Files.notExists(targetPath)) {
            ConfigFileUpdater.installBundledResource(plugin, "gui.yml", targetPath, false);
        }
    }
}
