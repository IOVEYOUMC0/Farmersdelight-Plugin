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

/**
 * Prepares the plugin's config.yml / gui.yml on disk: writes the bundled defaults when missing,
 * restores an unreadable/corrupt file from the jar (keeping a timestamped backup), atomically writes
 * a single bundled resource, migrates renamed config keys, and adds settings that newer plugin versions
 * introduced to a config file an admin already has. Extracted from the plugin main class so
 * the config-file bootstrap concern lives in one focused place. Operations run through the plugin's
 * public config API (getConfig / saveConfig / reloadConfig / getResource).
 *
 * The rename/retire/merge machinery itself lives in api/config so addons can run the same update over
 * their own files; what stays here is FarmersDelight's own data (the three tables below), its file
 * handling and its log lines.
 */
public final class ConfigBootstrap {

    /**
     * Old path to new path, applied in order. An entry names a whole section as well as a single key: the
     * migration moves the value at the old path, recursing into child sections, and then clears the old path.
     *
     * Order matters where a path is migrated twice. The four legacy drop names below land on
     * mob-extra-drops / mob-extra-drop-tools, which the entries after them move on to their place under
     * drops, so a config still using the oldest names is carried across in two hops during the same pass.
     * The knife list chains the same way: knife-config lands on drops.knife-items, which the entry after
     * it lifts to the top level.
     *
     * A section that moves back to a top-level path it used to have is still listed here. The rename is a
     * no-op for a file that never left the top level (the migration skips an entry whose new path is
     * already set), and for a file written while the section was nested it removes the nested copy, so an
     * admin is not left with the same section under two names.
     *
     * Settings that no longer have a reader are retired. A section migration carries every child across, so
     * a key the bundled file has dropped still arrives in the admin's file and reads like a working setting.
     * Each retired path is removed from the file and named in the log, so an admin who had tuned it learns
     * that the value now does nothing instead of finding it sitting next to the settings that still work.
     * Both legacy and migrated paths are listed: a file the migration reached carries the new path, a file
     * that already had the new section keeps the old one untouched.
     *
     * Registry sections are those whose children are content entries rather than fixed settings. An admin
     * disables one of these by deleting its entry, so the merge must not restore entries individually; it
     * only creates the section when the file has none of it yet. Renamed sections are listed under both
     * names. The new name covers a file the migration has already rewritten; the old name still has to be
     * honoured because the merge also runs against files the migration could not reach, and dropping it
     * would let the merge repopulate entries an admin deleted.
     */
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
                    "pet-foods",
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
            writeBundledResourceIfMissing("gui.yml", guiPath);

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

    /**
     * Brings an admin's config.yml and gui.yml up to date with the running plugin version, and must run
     * before the config values are read into fields.
     *
     * The two passes are ordered, not interchangeable. Renaming runs first: a rename is skipped when the new
     * path is already set, so filling in the bundled defaults first would plant the new path, make every
     * rename a no-op and silently drop the values the admin had configured under the old name. Only once the
     * old names have been carried over does the merge fill in the settings that are still absent.
     *
     * Retired keys are dropped between the two, after a rename has moved a value to its current path and
     * before the merge writes the file, so a setting nothing reads is not carried forward again.
     */
    public void migrateConfigKeys() {
        boolean changed = false;
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

    /**
     * Copies settings that exist in the jar's config.yml but not in the admin's file into the live
     * configuration, leaving every value the admin already set untouched. Returns the number of settings
     * added; the caller writes the file.
     */
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

    /**
     * Same additive merge for gui.yml. gui.yml is not the plugin's main config, so it is loaded, merged and
     * written here instead of through saveConfig. It is fed the same registry sections as config.yml, so the
     * two files cannot drift apart on what counts as a content registry.
     */
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

    /**
     * Deletes the retired settings from the admin's file and names them in one log line. Returns true when
     * the file changed, so the caller writes it.
     */
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

    /**
     * Keeps a timestamped copy before the plugin rewrites a file the admin owns. A failed backup must not
     * abort the rewrite, so it is reported and the caller continues.
     */
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

    private void writeBundledResourceIfMissing(String resourcePath, Path targetPath) throws IOException {
        if (Files.notExists(targetPath)) {
            ConfigFileUpdater.installBundledResource(plugin, resourcePath, targetPath, false);
        }
    }
}
