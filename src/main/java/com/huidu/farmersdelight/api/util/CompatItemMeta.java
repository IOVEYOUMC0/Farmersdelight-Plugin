package com.huidu.farmersdelight.api.util;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/** Item-model access on the supported Paper baseline. */
public final class CompatItemMeta {
    private CompatItemMeta() { }

    public static boolean isSupported() {
        return true;
    }

    public static void setItemModel(ItemMeta meta, NamespacedKey key) {
        if (meta != null && key != null) meta.setItemModel(key);
    }
}
