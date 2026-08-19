package com.huidu.farmersdelight.api.recipe;

import org.bukkit.inventory.ItemStack;

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
}
