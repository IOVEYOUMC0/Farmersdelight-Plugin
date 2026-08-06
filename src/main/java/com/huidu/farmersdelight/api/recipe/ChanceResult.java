package com.huidu.farmersdelight.api.recipe;

import org.bukkit.inventory.ItemStack;

import java.util.concurrent.ThreadLocalRandom;

public record ChanceResult(ItemStack item, float chance) {

    public ChanceResult {
        if (item == null) throw new IllegalArgumentException("item must not be null");
        if (chance < 0.0f || chance > 1.0f) throw new IllegalArgumentException("chance must be in [0,1]: " + chance);
        item = item.clone();
    }

    @Override
    public ItemStack item() {
        return item.clone();
    }

    public ItemStack roll() {
        if (chance <= 0.0f) return null;
        if (chance >= 1.0f) return item.clone();
        return ThreadLocalRandom.current().nextFloat() < chance ? item.clone() : null;
    }
}
