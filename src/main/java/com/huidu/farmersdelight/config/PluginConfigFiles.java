package com.huidu.farmersdelight.config;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;

/** Loads FD's auxiliary YAML files without mixing file I/O into runtime settings assembly. */
public final class PluginConfigFiles {

    private final FarmersDelightPlugin plugin;
    private final ConfigBootstrap bootstrap;

    public PluginConfigFiles(FarmersDelightPlugin plugin, ConfigBootstrap bootstrap) {
        this.plugin = plugin;
        this.bootstrap = bootstrap;
    }

    public YamlConfiguration loadWorldData() {
        return bootstrap.loadWorldDataConfig();
    }

    public YamlConfiguration loadDrops() {
        return bootstrap.loadDropsConfig();
    }

    public YamlConfiguration loadDisplayOverrides() {
        return bootstrap.loadDisplayOverridesConfig();
    }

    public YamlConfiguration loadGui() {
        Path path = plugin.getDataFolder().toPath().resolve("gui.yml");
        YamlConfiguration yaml = new YamlConfiguration();
        if (Files.notExists(path)) {
            if (plugin.isDebugEnabled("config")) {
                I18n.logInfo("plugin.gui_missing_defaults");
            }
            return yaml;
        }
        try {
            // Shares the validation pass's parse when this runs inside a reload.
            YamlConfiguration parsed = bootstrap.readGuiForLoad(path);
            if (parsed != null) {
                return parsed;
            }
        } catch (Exception e) {
            I18n.logWarning("plugin.gui_load_failed", "error", e.getMessage());
        }
        return yaml;
    }

    public PetFoodConfig loadPetFood(ConfigurationSection legacySection) {
        PetFoodConfig config = new PetFoodConfig();
        config.loadFromCraftEngine();
        if (legacySection != null) {
            config.mergeFromConfig(legacySection);
        }
        return config;
    }
}
