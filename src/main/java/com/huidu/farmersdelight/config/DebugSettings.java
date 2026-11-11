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

    /** The enum path used by internal call sites; a typo cannot compile past it. */
    public boolean enabledFor(DebugCategory category) {
        return enabled && category != null
                && (categories.contains("*") || categories.contains("all") || categories.contains(category.id()));
    }

    /** Configured category names the enum does not know; reported once when the config is read. */
    public java.util.List<String> unknownCategories() {
        return categories.stream()
                .filter(name -> !name.equals("*") && !name.equals("all") && DebugCategory.byId(name).isEmpty())
                .sorted()
                .toList();
    }

    public boolean enabledFor(String category) {
        if (!enabled || category == null || category.isBlank()) {
            return false;
        }
        String normalized = category.trim().toLowerCase(Locale.ROOT);
        return categories.contains("*") || categories.contains("all") || categories.contains(normalized);
    }
}
