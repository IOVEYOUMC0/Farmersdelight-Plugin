package com.huidu.farmersdelight.api.recipe;

import org.bukkit.inventory.ItemStack;

import java.util.concurrent.ThreadLocalRandom;

/**
 * A weighted cutting-board result, matching the mod's vectorwing.farmersdelight.common.crafting.ingredient.ChanceResult.
 * <p>
 * Each result has a chance() between 0.0 and 1.0. When the cutting board processes an
 * item, every configured result rolls independently on each cut — one cut can produce zero,
 * one, or multiple results.
 */
public record ChanceResult(ItemStack item, float chance) {

    public ChanceResult {
        if (item == null) throw new IllegalArgumentException("item must not be null");
        if (chance < 0.0f || chance > 1.0f) throw new IllegalArgumentException("chance must be in [0,1]: " + chance);
        item = item.clone();
    }

    /**
     * Returns a copy of the item.
     */
    @Override
    public ItemStack item() {
        return item.clone();
    }

    /**
     * Rolls the chance; returns a copy of the item on success, null on failure.
     */
    public ItemStack roll() {
        if (chance <= 0.0f) return null;
        if (chance >= 1.0f) return item.clone();
        return ThreadLocalRandom.current().nextFloat() < chance ? item.clone() : null;
    }
}
