package com.huidu.farmersdelight.api.item;

import org.bukkit.inventory.ItemStack;

/**
 * Compatibility delegate for {@link com.huidu.farmersdelight.api.compat.ce.SlotPlaceholder}.
 *
 * @deprecated use the CraftEngine-pinned {@code api.compat.ce} class for new code.
 */
@Deprecated
public final class SlotPlaceholder {

    private SlotPlaceholder() {
    }

    public static ItemStack mark(String key, ItemStack base) {
        return com.huidu.farmersdelight.api.compat.ce.SlotPlaceholder.mark(key, base);
    }

    public static boolean is(String key, ItemStack item) {
        return com.huidu.farmersdelight.api.compat.ce.SlotPlaceholder.is(key, item);
    }

    public static boolean isEmpty(String key, ItemStack item) {
        return com.huidu.farmersdelight.api.compat.ce.SlotPlaceholder.isEmpty(key, item);
    }
}
