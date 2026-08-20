package com.huidu.farmersdelight.api.enchant;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * An immutable description of a custom enchantment an addon wants FarmersDelight to manage. It maps 1:1 onto the
 * datapack enchantment JSON FarmersDelight writes (description/translate, weight, cost curve, anvil cost, slots,
 * distribution tags) plus which EnchantGroups may receive it in the enchanting table and anvil.
 *
 * The runtime effect is NOT part of this descriptor: an addon owns its own Bukkit listener and reads the enchant
 * level off the held item. All fields have sensible defaults matching FarmersDelight's built-in enchant; use
 * the builder.
 */
public final class EnchantmentDefinition {

    private final String id;
    private final String fallbackName;
    private final String translationKey;
    private final int weight;
    private final int maxLevel;
    private final int minCostBase;
    private final int minCostPerLevel;
    private final int maxCostBase;
    private final int maxCostPerLevel;
    private final int anvilCost;
    private final List<String> slots;
    private final Set<EnchantGroup> groups;
    private final boolean tradeable;
    private final boolean treasure;
    private final boolean onRandomLoot;

    private EnchantmentDefinition(Builder builder) {
        this.id = builder.id;
        this.fallbackName = builder.fallbackName;
        this.translationKey = builder.translationKey != null ? builder.translationKey : defaultTranslationKey(builder.id);
        this.weight = builder.weight;
        this.maxLevel = builder.maxLevel;
        this.minCostBase = builder.minCostBase;
        this.minCostPerLevel = builder.minCostPerLevel;
        this.maxCostBase = builder.maxCostBase;
        this.maxCostPerLevel = builder.maxCostPerLevel;
        this.anvilCost = builder.anvilCost;
        this.slots = List.copyOf(builder.slots);
        this.groups = Set.copyOf(builder.groups);
        this.tradeable = builder.tradeable;
        this.treasure = builder.treasure;
        this.onRandomLoot = builder.onRandomLoot;
    }

    public String id() {
        return id;
    }

    public String fallbackName() {
        return fallbackName;
    }

    public String translationKey() {
        return translationKey;
    }

    public int weight() {
        return weight;
    }

    public int maxLevel() {
        return maxLevel;
    }

    public int minCostBase() {
        return minCostBase;
    }

    public int minCostPerLevel() {
        return minCostPerLevel;
    }

    public int maxCostBase() {
        return maxCostBase;
    }

    public int maxCostPerLevel() {
        return maxCostPerLevel;
    }

    public int anvilCost() {
        return anvilCost;
    }

    public List<String> slots() {
        return slots;
    }

    public Set<EnchantGroup> groups() {
        return groups;
    }

    public boolean tradeable() {
        return tradeable;
    }

    public boolean treasure() {
        return treasure;
    }

    public boolean onRandomLoot() {
        return onRandomLoot;
    }

    private static String defaultTranslationKey(String id) {
        if (id == null) {
            return "enchantment.farmersdelight.unknown";
        }
        int separator = id.indexOf(':');
        if (separator <= 0 || separator == id.length() - 1) {
            return "enchantment.farmersdelight.unknown";
        }
        return "enchantment." + id.substring(0, separator) + "." + id.substring(separator + 1);
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public static final class Builder {
        private final String id;
        private String fallbackName;
        private String translationKey;
        private int weight = 4;
        private int maxLevel = 1;
        private int minCostBase = 15;
        private int minCostPerLevel = 9;
        private int maxCostBase = 50;
        private int maxCostPerLevel = 8;
        private int anvilCost = 2;
        private List<String> slots = List.of("mainhand");
        private Set<EnchantGroup> groups = Set.of(EnchantGroup.KNIVES);
        private boolean tradeable;
        private boolean treasure;
        private boolean onRandomLoot;

        private Builder(String id) {
            if (id == null || id.indexOf(':') <= 0) {
                throw new IllegalArgumentException("Enchantment id must be a namespaced id (namespace:path): " + id);
            }
            this.id = id.toLowerCase(Locale.ROOT);
            this.fallbackName = id;
        }

        public Builder fallbackName(String fallbackName) {
            this.fallbackName = fallbackName;
            return this;
        }

        public Builder translationKey(String translationKey) {
            this.translationKey = translationKey;
            return this;
        }

        public Builder weight(int weight) {
            this.weight = Math.max(1, weight);
            return this;
        }

        public Builder maxLevel(int maxLevel) {
            this.maxLevel = Math.max(1, maxLevel);
            return this;
        }

        public Builder minCost(int base, int perLevelAboveFirst) {
            this.minCostBase = base;
            this.minCostPerLevel = perLevelAboveFirst;
            return this;
        }

        public Builder maxCost(int base, int perLevelAboveFirst) {
            this.maxCostBase = base;
            this.maxCostPerLevel = perLevelAboveFirst;
            return this;
        }

        public Builder anvilCost(int anvilCost) {
            this.anvilCost = Math.max(0, anvilCost);
            return this;
        }

        public Builder slots(List<String> slots) {
            this.slots = slots == null || slots.isEmpty() ? List.of("mainhand") : List.copyOf(slots);
            return this;
        }

        public Builder groups(EnchantGroup... groups) {
            this.groups = groups == null || groups.length == 0 ? Set.of(EnchantGroup.KNIVES) : Set.of(groups);
            return this;
        }

        public Builder distribution(boolean tradeable, boolean treasure, boolean onRandomLoot) {
            this.tradeable = tradeable;
            this.treasure = treasure;
            this.onRandomLoot = onRandomLoot;
            return this;
        }

        public EnchantmentDefinition build() {
            return new EnchantmentDefinition(this);
        }
    }
}
