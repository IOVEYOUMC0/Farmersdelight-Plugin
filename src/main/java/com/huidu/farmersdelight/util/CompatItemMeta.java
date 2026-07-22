package com.huidu.farmersdelight.util;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Internal alias for the shared item_model compatibility helper that lives in
 * com.huidu.farmersdelight.api.util.CompatItemMeta. The api copy holds the only implementation (a
 * single reflective method lookup shared by the plugin and its addons); this class exists only so
 * internal call sites keep their short import.
 */
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
