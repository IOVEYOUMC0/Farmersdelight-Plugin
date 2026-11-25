package com.huidu.farmersdelight.api.config;

import com.huidu.farmersdelight.api.FarmersDelightApi;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * The two pieces every addon's config bootstrap repeats: merge the bundled config.yml through the shared
 * updater and log the report, and load an editable YAML resource after installing the bundled copy when it is
 * absent. The addon still owns its update policy and any custom migration it needs.
 */
public final class AddonConfigBootstrap {

    private final JavaPlugin addon;

    public AddonConfigBootstrap(JavaPlugin addon) {
        if (addon == null) {
            throw new NullPointerException("addon");
        }
        this.addon = addon;
    }

    /**
     * Merges the bundled main config into the deployed one and logs the family's update notices.
     * Call after saveDefaultConfig and before reading any config value.
     *
     * Returns true when the update ran; false when it failed and the addon should keep its current file.
     */
    public boolean updateMainConfig(ConfigUpdatePolicy policy) {
        if (policy == null) {
            return false;
        }
        ConfigUpdateReport report;
        try {
            report = ConfigFileUpdater.updateMainConfig(addon, policy);
        } catch (Exception failure) {
            addon.getLogger().warning(FarmersDelightApi.consoleMessage("plugin.config_merge_failed",
                    "file", "config.yml", "error", String.valueOf(failure.getMessage())));
            return false;
        }
        ConfigUpdateNotices.logAll(addon, "config.yml", report);
        return true;
    }

    /**
     * Loads an editable YAML resource, first writing the bundled copy when the operator has none.
     * Returns null when the file is unreadable; callers choose their own empty/default fallback.
     */
    public YamlConfiguration loadYamlResource(String resourceName) {
        if (resourceName == null || resourceName.isBlank()) {
            return null;
        }
        File file = new File(addon.getDataFolder(), resourceName);
        try {
            if (!file.exists()) {
                addon.saveResource(resourceName, false);
            }
            if (!file.isFile()) {
                return null;
            }
            return YamlConfiguration.loadConfiguration(file);
        } catch (RuntimeException failure) {
            addon.getLogger().warning(FarmersDelightApi.consoleMessage("plugin.config_merge_failed",
                    "file", resourceName, "error", String.valueOf(failure.getMessage())));
            return null;
        }
    }
}
