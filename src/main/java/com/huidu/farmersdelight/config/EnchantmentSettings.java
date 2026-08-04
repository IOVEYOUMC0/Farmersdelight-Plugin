package com.huidu.farmersdelight.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Immutable, reload-safe configuration for the knife enchantment system.
 *
 * <p>The listener deliberately reads weights, modified-level ranges, conflicts and anvil costs from
 * Paper's live enchantment registry.  This object only describes policy: which enchantments are allowed,
 * which item-specific enchantability fallback to use, and how the custom backstabbing definition/combat
 * effect should behave.</p>
 */
public record EnchantmentSettings(
        boolean enabled,
        Conflict conflict,
        Table table,
        Anvil anvil,
        Backstabbing backstabbing,
        Datapack datapack
) {

    private static final List<String> DEFAULT_CONFLICT_PLUGINS = List.of(
            "EcoEnchants", "AdvancedEnchantments", "ExcellentEnchants", "EnchantsSquared",
            "AeEnchants", "Zenchantments", "ElementalEnchants", "EnchantmentSolution"
    );

    private static final List<String> DEFAULT_TABLE_ENCHANTMENTS = List.of(
            "minecraft:sharpness",
            "minecraft:smite",
            "minecraft:bane_of_arthropods",
            "minecraft:unbreaking",
            "minecraft:fire_aspect",
            "minecraft:knockback",
            "minecraft:looting",
            "minecraft:efficiency",
            "minecraft:fortune",
            "$backstabbing"
    );

    private static final List<String> DEFAULT_ANVIL_ENCHANTMENTS;

    static {
        List<String> defaults = new ArrayList<>(DEFAULT_TABLE_ENCHANTMENTS);
        defaults.add("minecraft:mending");
        DEFAULT_ANVIL_ENCHANTMENTS = List.copyOf(defaults);
    }

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
        ConfigurationSection datapackSection = child(root, "datapack");

        boolean enabled = bool(root, "enabled", true);

        Conflict conflict = new Conflict(
                bool(conflictSection, "auto-disable-on-conflict", true),
                stringList(conflictSection, "plugins", DEFAULT_CONFLICT_PLUGINS, false)
        );

        Table table = new Table(
                bool(tableSection, "enabled", true),
                bool(tableSection, "override-offers", true),
                clampFinite(number(tableSection, "append-chance", 1.0D), 0.0D, 1.0D),
                bool(tableSection, "allow-enchanted-items", false),
                clamp(integer(tableSection, "default-enchantability", 12), 1, 1024),
                enchantabilityMap(child(tableSection, "item-enchantability")),
                stringList(tableSection, "enchantments", DEFAULT_TABLE_ENCHANTMENTS, true)
        );

        Anvil anvil = new Anvil(
                bool(anvilSection, "enabled", true),
                clamp(integer(anvilSection, "conflict-penalty", 1), 0, 1024),
                clamp(integer(anvilSection, "minimum-repair-cost", 1), 0, 32767),
                stringList(anvilSection, "enchantments", DEFAULT_ANVIL_ENCHANTMENTS, true)
        );

        String enchantmentId = namespaced(string(backstabSection, "id", "farmersdelight:backstabbing"),
                "farmersdelight:backstabbing");
        Backstabbing.Definition definition = new Backstabbing.Definition(
                clamp(integer(definitionSection, "weight", 5), 1, 1024),
                clamp(integer(definitionSection, "max-level", 3), 1, 255),
                clamp(integer(definitionSection, "min-cost-base", 15), 1, 32767),
                clamp(integer(definitionSection, "min-cost-per-level", 9), 0, 32767),
                clamp(integer(definitionSection, "max-cost-base", 50), 1, 32767),
                clamp(integer(definitionSection, "max-cost-per-level", 8), 0, 32767),
                clamp(integer(definitionSection, "anvil-cost", 2), 0, 32767),
                stringList(definitionSection, "slots", List.of("mainhand"), false),
                namespaced(string(definitionSection, "supported-items-tag",
                        "farmersdelight:enchantable/knife"), "farmersdelight:enchantable/knife"),
                stringList(definitionSection, "supported-items", List.of(), true),
                string(definitionSection, "fallback-name", "Backstabbing")
        );

        Backstabbing.Combat combat = new Backstabbing.Combat(
                bool(combatSection, "players-only", true),
                bool(combatSection, "require-knife", true),
                clampFinite(number(combatSection, "multiplier-base", 1.4D), 0.0D, 100.0D),
                clampFinite(number(combatSection, "multiplier-per-level", 0.2D), 0.0D, 100.0D),
                clampFinite(number(combatSection, "behind-dot-threshold", -0.5D), -1.0D, 1.0D),
                clampFinite(number(combatSection, "minimum-horizontal-distance", 0.001D), 0.0D, 100.0D),
                namespaced(string(combatSection, "sound", "minecraft:entity.player.attack.crit"),
                        "minecraft:entity.player.attack.crit"),
                (float) clampFinite(number(combatSection, "sound-volume", 1.0D), 0.0D, 100.0D),
                (float) clampFinite(number(combatSection, "sound-pitch", 1.0D), 0.01D, 2.0D),
                parseSoundLocation(string(combatSection, "sound-location", "target"))
        );

        Backstabbing backstabbing = new Backstabbing(
                bool(backstabSection, "enabled", true),
                enchantmentId,
                definition,
                combat
        );

        Datapack datapack = new Datapack(
                bool(datapackSection, "enabled", true),
                safePathSegment(string(datapackSection, "directory", "farmersdelight_enchant"),
                        "farmersdelight_enchant"),
                clamp(integer(datapackSection, "pack-format", 61), 1, 9999),
                string(datapackSection, "description", "FarmersDelight configurable enchantments")
        );

        return new EnchantmentSettings(enabled, conflict, table, anvil, backstabbing, datapack);
    }

    public boolean isBackstabbingConfigured() {
        return enabled && backstabbing.enabled();
    }

    public record Conflict(boolean autoDisableOnConflict, List<String> plugins) {
        public Conflict {
            plugins = List.copyOf(plugins);
        }
    }

    public record Table(
            boolean enabled,
            boolean overrideOffers,
            double appendChance,
            boolean allowEnchantedItems,
            int defaultEnchantability,
            Map<String, Integer> itemEnchantability,
            List<String> enchantments
    ) {
        public Table {
            itemEnchantability = Map.copyOf(itemEnchantability);
            enchantments = List.copyOf(enchantments);
        }

        public int enchantabilityFor(Set<String> itemIds) {
            Integer configured = configuredEnchantabilityFor(itemIds);
            return configured == null ? defaultEnchantability : configured;
        }

        public Integer configuredEnchantabilityFor(Set<String> itemIds) {
            for (String itemId : itemIds) {
                if (itemId.toLowerCase(Locale.ROOT).startsWith("minecraft:")) {
                    continue;
                }
                Integer configured = itemEnchantability.get(itemId.toLowerCase(Locale.ROOT));
                if (configured != null) {
                    return configured;
                }
            }
            for (String itemId : itemIds) {
                Integer configured = itemEnchantability.get(itemId.toLowerCase(Locale.ROOT));
                if (configured != null) {
                    return configured;
                }
            }
            return null;
        }
    }

    public record Anvil(
            boolean enabled,
            int conflictPenalty,
            int minimumRepairCost,
            List<String> enchantments
    ) {
        public Anvil {
            enchantments = List.copyOf(enchantments);
        }
    }

    public record Backstabbing(
            boolean enabled,
            String id,
            Definition definition,
            Combat combat
    ) {
        public record Definition(
                int weight,
                int maxLevel,
                int minCostBase,
                int minCostPerLevel,
                int maxCostBase,
                int maxCostPerLevel,
                int anvilCost,
                List<String> slots,
                String supportedItemsTag,
                List<String> supportedItems,
                String fallbackName
        ) {
            public Definition {
                slots = List.copyOf(slots);
                supportedItems = List.copyOf(supportedItems);
            }
        }

        public record Combat(
                boolean playersOnly,
                boolean requireKnife,
                double multiplierBase,
                double multiplierPerLevel,
                double behindDotThreshold,
                double minimumHorizontalDistance,
                String sound,
                float soundVolume,
                float soundPitch,
                SoundLocation soundLocation
        ) {
            public double multiplier(int level) {
                return multiplierBase + Math.max(0, level - 1) * multiplierPerLevel;
            }

            public double minimumHorizontalDistanceSquared() {
                return minimumHorizontalDistance * minimumHorizontalDistance;
            }
        }
    }

    public enum SoundLocation {
        ATTACKER,
        TARGET
    }

    public record Datapack(boolean enabled, String directory, int packFormat, String description) {
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

    private static Map<String, Integer> enchantabilityMap(ConfigurationSection section) {
        if (section == null) {
            return Map.of();
        }
        Map<String, Integer> values = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            String normalized = namespaced(key, null);
            if (normalized != null) {
                values.put(normalized, clamp(section.getInt(key, 12), 1, 1024));
            }
        }
        return values;
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

    private static String safePathSegment(String value, String fallback) {
        String normalized = value == null ? "" : value.trim();
        return normalized.matches("[A-Za-z0-9_.-]+")
                && !".".equals(normalized)
                && !"..".equals(normalized)
                ? normalized
                : fallback;
    }

    private static SoundLocation parseSoundLocation(String value) {
        return "attacker".equalsIgnoreCase(value) ? SoundLocation.ATTACKER : SoundLocation.TARGET;
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
