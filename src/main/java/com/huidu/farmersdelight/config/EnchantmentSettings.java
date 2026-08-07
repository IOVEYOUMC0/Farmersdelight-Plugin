package com.huidu.farmersdelight.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record EnchantmentSettings(
        boolean enabled,
        boolean autoDisableOnConflict,
        Group knives,
        Group skillet,
        Backstabbing backstabbing
) {

    public EnchantmentSettings(
            boolean enabled,
            boolean autoDisableOnConflict,
            Table table,
            boolean anvilEnabled,
            Backstabbing backstabbing
    ) {
        this(
                enabled,
                autoDisableOnConflict,
                new Group(table, anvilEnabled),
                new Group(table, anvilEnabled),
                backstabbing
        );
    }

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

    private static final List<String> DEFAULT_SKILLET_ENCHANTMENTS = List.of(
            "minecraft:sharpness",
            "minecraft:smite",
            "minecraft:bane_of_arthropods",
            "minecraft:unbreaking",
            "minecraft:fire_aspect",
            "minecraft:knockback",
            "minecraft:looting"
    );

    public static EnchantmentSettings defaults() {
        return load(null);
    }

    public static EnchantmentSettings load(ConfigurationSection root) {
        ConfigurationSection conflictSection = child(root, "compatibility");
        ConfigurationSection legacyTableSection = child(root, "table");
        ConfigurationSection legacyAnvilSection = child(root, "anvil");
        ConfigurationSection groupsSection = child(root, "groups");
        ConfigurationSection backstabSection = child(root, "backstabbing");
        ConfigurationSection definitionSection = child(backstabSection, "definition");
        ConfigurationSection combatSection = child(backstabSection, "combat");

        boolean enabled = bool(root, "enabled", true);
        boolean autoDisableOnConflict = bool(conflictSection, "auto-disable-on-conflict", true);

        Group knives = loadGroup(
                child(groupsSection, "knives"),
                legacyTableSection,
                legacyAnvilSection,
                12,
                DEFAULT_TABLE_ENCHANTMENTS
        );
        Group skillet = loadGroup(
                child(groupsSection, "skillet"),
                legacyTableSection,
                legacyAnvilSection,
                14,
                DEFAULT_SKILLET_ENCHANTMENTS
        );

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

        return new EnchantmentSettings(enabled, autoDisableOnConflict, knives, skillet, backstabbing);
    }

    public boolean isBackstabbingConfigured() {
        return enabled && backstabbing.enabled();
    }

    public Group group(GroupId groupId) {
        return groupId == GroupId.SKILLET ? skillet : knives;
    }

    public Table table() {
        return knives.table();
    }

    public boolean anvilEnabled() {
        return knives.anvilEnabled();
    }

    public enum GroupId {
        KNIVES,
        SKILLET
    }

    public record Group(Table table, boolean anvilEnabled) {
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

    private static Group loadGroup(
            ConfigurationSection groupSection,
            ConfigurationSection legacyTableSection,
            ConfigurationSection legacyAnvilSection,
            int defaultEnchantability,
            List<String> defaultEnchantments
    ) {
        ConfigurationSection tableSection = child(groupSection, "table");
        ConfigurationSection anvilSection = child(groupSection, "anvil");
        Table table = new Table(
                bool(tableSection, legacyTableSection, "enabled", true),
                bool(tableSection, legacyTableSection, "override-offers", true),
                clamp(integer(
                        tableSection,
                        legacyTableSection,
                        "default-enchantability",
                        defaultEnchantability
                ), 1, 1024),
                stringList(tableSection, legacyTableSection, "enchantments", defaultEnchantments, true)
        );
        return new Group(table, bool(anvilSection, legacyAnvilSection, "enabled", true));
    }

    private static boolean bool(ConfigurationSection section, String path, boolean fallback) {
        return section == null ? fallback : section.getBoolean(path, fallback);
    }

    private static boolean bool(
            ConfigurationSection section,
            ConfigurationSection legacySection,
            String path,
            boolean fallback
    ) {
        if (section != null && section.isSet(path)) {
            return section.getBoolean(path, fallback);
        }
        return bool(legacySection, path, fallback);
    }

    private static int integer(ConfigurationSection section, String path, int fallback) {
        return section == null ? fallback : section.getInt(path, fallback);
    }

    private static int integer(
            ConfigurationSection section,
            ConfigurationSection legacySection,
            String path,
            int fallback
    ) {
        if (section != null && section.isSet(path)) {
            return section.getInt(path, fallback);
        }
        return integer(legacySection, path, fallback);
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

    private static List<String> stringList(
            ConfigurationSection section,
            ConfigurationSection legacySection,
            String path,
            List<String> fallback,
            boolean normalizeNamespacedIds
    ) {
        if (section != null && section.isList(path)) {
            return stringList(section, path, fallback, normalizeNamespacedIds);
        }
        return stringList(legacySection, path, fallback, normalizeNamespacedIds);
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
