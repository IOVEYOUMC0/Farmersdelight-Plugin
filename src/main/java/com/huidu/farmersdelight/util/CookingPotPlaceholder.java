package com.huidu.farmersdelight.util;

import com.huidu.farmersdelight.api.item.SlotPlaceholder;
import org.bukkit.inventory.ItemStack;

public final class CookingPotPlaceholder {

    private static final String KEY = "farmersdelight_placeholder";

    private CookingPotPlaceholder() {
    }

    public static ItemStack mark(ItemStack base) {
        return SlotPlaceholder.mark(KEY, base);
    }

    public static boolean is(ItemStack item) {
        return SlotPlaceholder.is(KEY, item);
    }

    public static boolean isEmpty(ItemStack item) {
        return SlotPlaceholder.isEmpty(KEY, item);
    }
}
