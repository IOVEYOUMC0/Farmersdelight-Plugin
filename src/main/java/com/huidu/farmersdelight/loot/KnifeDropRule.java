package com.huidu.farmersdelight.loot;

public record KnifeDropRule(String entityType, String normalItem, String burningItem, double baseChance,
                            double lootingMultiplier) {
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
}

