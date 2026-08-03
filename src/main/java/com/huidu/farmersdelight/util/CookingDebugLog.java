package com.huidu.farmersdelight.util;

import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;

/**
 * Shared console debug logging used by the stove and skillet cooking behaviors.
 */
public final class CookingDebugLog {

    private CookingDebugLog() {
    }

    @SuppressWarnings("UnstableApiUsage")
    public static void logField(String labelKey, Object value) {
        Bukkit.getLogger().info(I18n.formatConsole("debug.field",
                "label", I18n.formatConsole(labelKey),
                "value", value));
    }

    public static String resolveItemId(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "minecraft:air";
        }

        String customItemId = ItemUtils.getCustomItemId(item);
        if (customItemId != null) {
            return customItemId;
        }
        return "minecraft:" + item.getType().name().toLowerCase(Locale.ROOT);
    }
}
