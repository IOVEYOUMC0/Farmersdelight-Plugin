package com.huidu.farmersdelight.config;

import com.huidu.farmersdelight.api.config.ConfigFileUpdater;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bundled resources parsed once per running server.
 *
 * <p>The bundled copies live inside the plugin jar and cannot change while the process runs, so every reload
 * would otherwise re-read and re-parse the same YAML — and config.yml is parsed on each of the three
 * passes a reload makes (type validation, key merge, GUI merge). The cache is keyed by resource path and
 * filled on first use.
 *
 * <p>Only the framework-owned files go through here. The public ConfigFileUpdater entry points stay
 * uncached: they are api surface and callers may legitimately expect a fresh parse.
 */
final class ConfigResources {

    private static final Map<String, Object> CACHE = new ConcurrentHashMap<>();
    private static final Object MISSING = new Object();

    private ConfigResources() {
    }

    /** Parsed bundled YAML, or null when the jar does not carry the resource. */
    static YamlConfiguration yaml(Plugin plugin, String resourcePath) throws IOException {
        Object cached = CACHE.get(resourcePath);
        if (cached != null) {
            return cached == MISSING ? null : (YamlConfiguration) cached;
        }
        YamlConfiguration parsed = parse(plugin, resourcePath);
        CACHE.put(resourcePath, parsed == null ? MISSING : parsed);
        return parsed;
    }

    private static YamlConfiguration parse(Plugin plugin, String resourcePath) throws IOException {
        try {
            return ConfigFileUpdater.readBundledYaml(plugin, resourcePath);
        } catch (InvalidConfigurationException e) {
            throw new IOException("Bundled " + resourcePath + " is not valid YAML: " + e.getMessage(), e);
        }
    }
}
