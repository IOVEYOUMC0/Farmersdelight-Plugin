package com.huidu.farmersdelight.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public record DebugSettings(boolean enabled, Set<String> categories) {
    public static DebugSettings load(ConfigurationSection config) {
        boolean enabled = config.getBoolean("debug", false) || config.getBoolean("debug.enabled", false);
        Set<String> categories = config.getStringList("debug.categories").stream()
                .filter(Objects::nonNull).map(String::trim).map(s -> s.toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
        return new DebugSettings(enabled, categories);
    }

    public boolean enabledFor(String category) {
        if (!enabled || category == null || category.isBlank()) {
            return false;
        }
        String normalized = category.trim().toLowerCase(Locale.ROOT);
        return categories.contains("*") || categories.contains("all") || categories.contains(normalized);
    }
}
