package com.huidu.farmersdelight.util;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;

import java.lang.reflect.Method;

/**
 * Applies the item_model component when the running server supports it, and is a no-op otherwise. The
 * ItemMeta setItemModel method exists only on Minecraft 1.21.4 and newer; on 1.21 / 1.21.1 the method
 * is absent, so the call is routed through reflection and skipped when unavailable. This lets the
 * plugin compile against and run on 1.21 while still honouring the component on newer servers.
 */
public final class CompatItemMeta {

    private static final Method SET_ITEM_MODEL = findSetItemModel();

    private CompatItemMeta() {
    }

    private static Method findSetItemModel() {
        try {
            return ItemMeta.class.getMethod("setItemModel", NamespacedKey.class);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean isSupported() {
        return SET_ITEM_MODEL != null;
    }

    public static void setItemModel(ItemMeta meta, NamespacedKey key) {
        if (SET_ITEM_MODEL == null || meta == null || key == null) {
            return;
        }
        try {
            SET_ITEM_MODEL.invoke(meta, key);
        } catch (ReflectiveOperationException ignored) {
        }
    }
}
