package com.huidu.farmersdelight.config;

import com.huidu.farmersdelight.api.config.ConfigSectionReader;
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
        Group durable,
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
                new Group(table, anvilEnabled),
                backstabbing
        );
    }

    // Applied by anvil but never offered by the enchanting table, matching vanilla's treatment of mending.
    private static final List<String> DEFAULT_ANVIL_EXTRA_ENCHANTMENTS = List.of("minecraft:mending");

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

    // Durability-only custom items (the farmersdelight:durable setting) are not weapons, so they default
    // to a minimal whitelist: unbreaking and vanishing_curse on the table; the anvil inherits both and
    // also allows mending. Without this, every vanilla enchant could be applied to such items.
    private static final List<String> DEFAULT_DURABLE_ENCHANTMENTS = List.of(
            "minecraft:unbreaking",
            "minecraft:vanishing_curse"
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
        Group durable = loadGroup(
                child(groupsSection, "durable"),
                legacyTableSection,
                legacyAnvilSection,
                14,
                DEFAULT_DURABLE_ENCHANTMENTS
        );

        String enchantmentId = namespaced(string(backstabSection),
                "farmersdelight:backstabbing");
        Backstabbing.Definition definition = new Backstabbing.Definition(
                clamp(integer(definitionSection, "weight", 5), 1024),
                clamp(integer(definitionSection, "max-level", 3), 255)
        );

        Backstabbing.Combat combat = new Backstabbing.Combat(
                bool(combatSection, "players-only", true),
                bool(combatSection, "require-knife", true),
                clampFinite(number(combatSection, "multiplier-base", 1.4D)),
                clampFinite(number(combatSection, "multiplier-per-level", 0.2D))
        );

        Backstabbing backstabbing = new Backstabbing(
                bool(backstabSection, "enabled", true),
                enchantmentId,
                definition,
                combat
        );

        return new EnchantmentSettings(enabled, autoDisableOnConflict, knives, skillet, durable, backstabbing);
    }

    public boolean isBackstabbingConfigured() {
        return enabled && backstabbing.enabled();
    }

    public Group group(GroupId groupId) {
        return switch (groupId) {
            case SKILLET -> skillet;
            case DURABLE -> durable;
            case KNIVES -> knives;
        };
    }

    public Table table() {
        return knives.table();
    }

    public boolean anvilEnabled() {
        return knives.anvilEnabled();
    }

    public enum GroupId {
        KNIVES,
        SKILLET,
        DURABLE
    }

    /**
     * One item group's enchanting rules. The anvil list is ADDITIVE on top of the table list, never a
     * replacement: an enchant the table offers can always also be applied by anvil, while extraEnchantments
     * holds the ones that are anvil-only. Mending is the built-in example -- the enchanting table never
     * offers it in vanilla either -- and putting it in config lets a server add its own without those
     * enchants leaking into the table's offer rolls.
     */
    public record Group(Table table, boolean anvilEnabled, List<String> extraEnchantments) {
        public Group {
            extraEnchantments = List.copyOf(extraEnchantments);
        }

        public Group(Table table, boolean anvilEnabled) {
            this(table, anvilEnabled, DEFAULT_ANVIL_EXTRA_ENCHANTMENTS);
        }
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
                bool(tableSection, legacyTableSection, "enabled"),
                bool(tableSection, legacyTableSection, "override-offers"),
                clamp(integer(
                        tableSection,
                        legacyTableSection,
                        defaultEnchantability
                ), 1024),
                stringList(tableSection, legacyTableSection, defaultEnchantments)
        );
        return new Group(
                table,
                bool(anvilSection, legacyAnvilSection, "enabled"),
                anvilExtras(anvilSection, legacyAnvilSection));
    }

    private static boolean bool(ConfigurationSection section, String path, boolean fallback) {
        return section == null ? fallback : section.getBoolean(path, fallback);
    }

    private static boolean bool(
            ConfigurationSection section,
            ConfigurationSection legacySection,
            String path
    ) {
        if (section != null && section.isSet(path)) {
            return section.getBoolean(path, true);
        }
        return bool(legacySection, path, true);
    }

    private static int integer(ConfigurationSection section, String path, int fallback) {
        return section == null ? fallback : section.getInt(path, fallback);
    }

    private static int integer(
            ConfigurationSection section,
            ConfigurationSection legacySection,
            int fallback
    ) {
        if (section != null && section.isSet("default-enchantability")) {
            return section.getInt("default-enchantability", fallback);
        }
        return integer(legacySection, "default-enchantability", fallback);
    }

    private static double number(ConfigurationSection section, String path, double fallback) {
        return section == null ? fallback : section.getDouble(path, fallback);
    }

    private static String string(ConfigurationSection section) {
        if (section == null) {
            return "farmersdelight:backstabbing";
        }
        String value = ConfigSectionReader.optionalString(section, "id");
        return value == null || value.isBlank() ? "farmersdelight:backstabbing" : value.trim();
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

    // Anvil-only additions. Absent from config means the built-in default (mending); an explicitly empty
    // list means the operator wants the anvil to accept exactly what the table offers and nothing more.
    private static List<String> anvilExtras(ConfigurationSection section, ConfigurationSection legacySection) {
        if (section != null && section.isList("extra-enchantments")) {
            return stringList(section, "extra-enchantments", DEFAULT_ANVIL_EXTRA_ENCHANTMENTS, true);
        }
        return stringList(legacySection, "extra-enchantments", DEFAULT_ANVIL_EXTRA_ENCHANTMENTS, true);
    }

    private static List<String> stringList(
            ConfigurationSection section,
            ConfigurationSection legacySection,
            List<String> fallback
    ) {
        if (section != null && section.isList("enchantments")) {
            return stringList(section, "enchantments", fallback, true);
        }
        return stringList(legacySection, "enchantments", fallback, true);
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

    private static int clamp(int value, int maximum) {
        return Math.max(1, Math.min(maximum, value));
    }

    private static double clampFinite(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(100.0, value));
    }
}
