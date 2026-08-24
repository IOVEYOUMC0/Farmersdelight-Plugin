package com.huidu.farmersdelight.api.config;

import org.bukkit.configuration.ConfigurationSection;

/**
 * A required config key was absent (or present but with a null value). Matches SparrowYAML's semantic
 * that a null YAML value is equivalent to the key being missing.
 */
public class MissingConfigKeyException extends ConfigParsingException {

    public MissingConfigKeyException(ConfigurationSection section, String path) {
        super(path, "Missing required config key '" + path
                + "' under '" + (section == null ? "?" : section.getCurrentPath()) + "'");
    }
}