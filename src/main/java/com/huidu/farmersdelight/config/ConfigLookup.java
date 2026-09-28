package com.huidu.farmersdelight.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.List;

/** Small read-only helpers for config aliases used during migration. */
public final class ConfigLookup {

    private ConfigLookup() {
    }

    /**
     * First alias that resolves to a section. A scalar at path is skipped rather than returned: the
     * caller asked for a section, and ConfigurationSection#getConfigurationSection(String) answers
     * null for one.
     */
    public static ConfigurationSection firstSection(ConfigurationSection config, String... paths) {
        if (config == null || paths == null) {
            return null;
        }
        for (String path : paths) {
            if (path == null) {
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
        if (config == null || paths == null) {
            return defaultValue;
        }
        // Stops at the first alias the file actually carries, like every other reader: the aliases are ordered
        // most-specific-first, so a later one must not win just because the earlier value is absent.
        for (String path : paths) {
            if (path != null && config.isSet(path)) {
                return config.getBoolean(path, defaultValue);
            }
        }
        return defaultValue;
    }

    public static double doubleValue(ConfigurationSection config, double defaultValue, String... paths) {
        if (config == null || paths == null) {
            return defaultValue;
        }
        for (String path : paths) {
            if (path != null && config.isSet(path)) {
                return config.getDouble(path, defaultValue);
            }
        }
        return defaultValue;
    }

    public static int intValue(ConfigurationSection config, int defaultValue, String... paths) {
        if (config == null || paths == null) {
            return defaultValue;
        }
        for (String path : paths) {
            if (path != null && config.isSet(path)) {
                return config.getInt(path, defaultValue);
            }
        }
        return defaultValue;
    }

    public static List<String> stringList(ConfigurationSection config, String... paths) {
        if (config == null || paths == null) {
            return List.of();
        }
        for (String path : paths) {
            if (path != null && config.isSet(path)) {
                return config.getStringList(path);
            }
        }
        return List.of();
    }
}
