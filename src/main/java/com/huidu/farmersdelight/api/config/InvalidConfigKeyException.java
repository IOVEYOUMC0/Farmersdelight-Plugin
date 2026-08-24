package com.huidu.farmersdelight.api.config;

import org.bukkit.configuration.ConfigurationSection;

/**
 * A config key exists but its value cannot be coerced to the requested type.
 */
public class InvalidConfigKeyException extends ConfigParsingException {

    public InvalidConfigKeyException(ConfigurationSection section, String path, Object value, String expected, Throwable cause) {
        super(path, "Invalid value '" + value + "' for config key '" + path
                + "' under '" + (section == null ? "?" : section.getCurrentPath())
                + "' (expected " + expected + ")", cause);
    }
}