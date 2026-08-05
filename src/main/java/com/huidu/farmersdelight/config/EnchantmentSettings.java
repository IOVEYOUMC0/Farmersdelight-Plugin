package com.huidu.farmersdelight.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Immutable, reload-safe configuration for the knife enchantment system.
 *
 * <p>The listener deliberately reads weights, modified-level ranges, conflicts and anvil costs from
 * Paper's live enchantment registry. This object only describes policy: which enchantments are allowed
 * and how the custom backstabbing effect should behave.</p>
 */
public record EnchantmentSettings(
        boolean enabled,
        boolean autoDisableOnConflict,
        Table table,
        boolean anvilEnabled,
        Backstabbing backstabbing
) {

    private static final List<String> DEFAULT_TABLE_ENCHANTMENTS = List.of(
            "minecraft:sharpness",
            "minecraft:smite",
            "minecraft:bane_of_arthropods",
            "minecraft:unbreaking",
            "minecraft:fire_aspect",
            "minecraft:knockback",
            "minecraft:looting",
            "minecraft:fortune",
            "$backstabbing"
    );

    public static EnchantmentSettings defaults() {
        return load(null);
    }

    public static EnchantmentSettings load(ConfigurationSection root) {
        ConfigurationSection conflictSection = child(root, "compatibility");
        ConfigurationSection tableSection = child(root, "table");
        ConfigurationSection anvilSection = child(root, "anvil");
        ConfigurationSection backstabSection = child(root, "backstabbing");
        ConfigurationSection definitionSection = child(backstabSection, "definition");
        ConfigurationSection combatSection = child(backstabSection, "combat");

        boolean enabled = bool(root, "enabled", true);
        boolean autoDisableOnConflict = bool(conflictSection, "auto-disable-on-conflict", true);

        Table table = new Table(
                bool(tableSection, "enabled", true),
                bool(tableSection, "override-offers", true),
                clamp(integer(tableSection, "default-enchantability", 12), 1, 1024),
                stringList(tableSection, "enchantments", DEFAULT_TABLE_ENCHANTMENTS, true)
        );

        boolean anvilEnabled = bool(anvilSection, "enabled", true);

        String enchantmentId = namespaced(string(backstabSection, "id", "farmersdelight:backstabbing"),
                "farmersdelight:backstabbing");
        Backstabbing.Definition definition = new Backstabbing.Definition(
                clamp(integer(definitionSection, "weight", 5), 1, 1024),
                clamp(integer(definitionSection, "max-level", 3), 1, 255)
        );

        Backstabbing.Combat combat = new Backstabbing.Combat(
                bool(combatSection, "players-only", true),
                bool(combatSection, "require-knife", true),
                clampFinite(number(combatSection, "multiplier-base", 1.4D), 0.0D, 100.0D),
                clampFinite(number(combatSection, "multiplier-per-level", 0.2D), 0.0D, 100.0D)
        );

        Backstabbing backstabbing = new Backstabbing(
                bool(backstabSection, "enabled", true),
                enchantmentId,
                definition,
                combat
        );

        return new EnchantmentSettings(enabled, autoDisableOnConflict, table, anvilEnabled, backstabbing);
    }

    public boolean isBackstabbingConfigured() {
        return enabled && backstabbing.enabled();
    }

    public record Table(
            boolean enabled,
            boolean overrideOffers,
            int defaultEnchantability,
            List<String> enchantments
    ) {
        public Table {
            enchantments = List.copyOf(enchantments);
        }
    }

    public record Backstabbing(
            boolean enabled,
            String id,
            Definition definition,
            Combat combat
    ) {
        public record Definition(int weight, int maxLevel) {
        }

        public record Combat(
                boolean playersOnly,
                boolean requireKnife,
                double multiplierBase,
                double multiplierPerLevel
        ) {
            public double multiplier(int level) {
                return multiplierBase + Math.max(0, level - 1) * multiplierPerLevel;
            }
        }
    }

    private static ConfigurationSection child(ConfigurationSection section, String path) {
        return section == null ? null : section.getConfigurationSection(path);
    }

    private static boolean bool(ConfigurationSection section, String path, boolean fallback) {
        return section == null ? fallback : section.getBoolean(path, fallback);
    }

    private static int integer(ConfigurationSection section, String path, int fallback) {
        return section == null ? fallback : section.getInt(path, fallback);
    }

    private static double number(ConfigurationSection section, String path, double fallback) {
        return section == null ? fallback : section.getDouble(path, fallback);
    }

    private static String string(ConfigurationSection section, String path, String fallback) {
        if (section == null) {
            return fallback;
        }
        String value = section.getString(path);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static List<String> stringList(
            ConfigurationSection section,
            String path,
            List<String> fallback,
            boolean normalizeNamespacedIds
    ) {
        if (section == null || !section.isList(path)) {
            return List.copyOf(fallback);
        }
        Set<String> values = new LinkedHashSet<>();
        for (String raw : section.getStringList(path)) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String value = raw.trim();
            if (normalizeNamespacedIds) {
                value = "$backstabbing".equalsIgnoreCase(value)
                        ? "$backstabbing"
                        : namespaced(value, null);
                if (value == null) {
                    continue;
                }
            }
            values.add(value);
        }
        return List.copyOf(values);
    }

    private static String namespaced(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            return fallback;
        }
        String path = normalized.substring(normalized.indexOf(':') + 1);
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                return fallback;
            }
        }
        return normalized;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double clampFinite(double value, double minimum, double maximum) {
        if (!Double.isFinite(value)) {
            return minimum;
        }
        return Math.max(minimum, Math.min(maximum, value));
    }
}
