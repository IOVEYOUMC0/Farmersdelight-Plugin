package com.huidu.farmersdelight.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.List;
import java.util.Arrays;

/** Small read-only helpers for config aliases used during migration. */
public final class ConfigLookup {

    private ConfigLookup() {
    }

    public static ConfigurationSection firstSection(ConfigurationSection config, String... paths) {
        if (config == null || paths == null) {
            return null;
        }
        for (String path : paths) {
            if (path == null || !config.contains(path, true)) {
                continue;
            }
            ConfigurationSection section = config.getConfigurationSection(path);
            if (section != null) {
                return section;
            }
        }
        return null;
    }

    public static boolean booleanValue(ConfigurationSection config, boolean defaultValue, String... paths) {
        for (String path : presentPaths(config, paths)) {
            return config.getBoolean(path, defaultValue);
        }
        return defaultValue;
    }

    public static double doubleValue(ConfigurationSection config, double defaultValue, String... paths) {
        for (String path : presentPaths(config, paths)) {
            return config.getDouble(path, defaultValue);
        }
        return defaultValue;
    }

    public static int intValue(ConfigurationSection config, int defaultValue, String... paths) {
        for (String path : presentPaths(config, paths)) {
            return config.getInt(path, defaultValue);
        }
        return defaultValue;
    }

    public static List<String> stringList(ConfigurationSection config, String... paths) {
        for (String path : presentPaths(config, paths)) {
            return config.getStringList(path);
        }
        return List.of();
    }

    private static List<String> presentPaths(ConfigurationSection config, String[] paths) {
        if (config == null || paths == null) {
            return List.of();
        }
        return Arrays.stream(paths)
                .filter(path -> path != null && config.contains(path, true))
                .toList();
    }
}
