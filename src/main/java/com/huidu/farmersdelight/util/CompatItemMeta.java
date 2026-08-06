package com.huidu.farmersdelight.util;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

public final class CompatItemMeta {

    private CompatItemMeta() {
    }

    public static boolean isSupported() {
        return com.huidu.farmersdelight.api.util.CompatItemMeta.isSupported();
    }

    public static void setItemModel(ItemMeta meta, NamespacedKey key) {
        com.huidu.farmersdelight.api.util.CompatItemMeta.setItemModel(meta, key);
    }
}
