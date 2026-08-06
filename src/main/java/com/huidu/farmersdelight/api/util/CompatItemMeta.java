package com.huidu.farmersdelight.api.util;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.ApiStatus;

import java.lang.reflect.Method;

@ApiStatus.NonExtendable
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
