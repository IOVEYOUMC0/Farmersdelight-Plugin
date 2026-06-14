package com.huidu.farmersdelight.loot;

import java.util.List;

public record KnifeDropRule(String entityType, String normalItem, String burningItem, double baseChance,
                            double lootingMultiplier, List<String> toolItems, List<String> toolTags) {
    public KnifeDropRule(String entityType, String normalItem, String burningItem, double baseChance,
                         double lootingMultiplier) {
        this(entityType, normalItem, burningItem, baseChance, lootingMultiplier, List.of(), List.of());
    }

    public KnifeDropRule {
        toolItems = toolItems == null ? List.of() : List.copyOf(toolItems);
        toolTags = toolTags == null ? List.of() : List.copyOf(toolTags);
    }

    public String getEntityType() {
        return entityType;
    }

    public String getNormalItem() {
        return normalItem;
    }

    public String getBurningItem() {
        return burningItem;
    }

    public double getBaseChance() {
        return baseChance;
    }

    public double getLootingMultiplier() {
        return lootingMultiplier;
    }

    public List<String> getToolItems() {
        return toolItems;
    }

    public List<String> getToolTags() {
        return toolTags;
    }

    public boolean hasToolMatchers() {
        return !toolItems.isEmpty() || !toolTags.isEmpty();
    }
}
