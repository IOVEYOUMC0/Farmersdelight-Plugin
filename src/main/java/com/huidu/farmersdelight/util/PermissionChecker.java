package com.huidu.farmersdelight.util;

import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class PermissionChecker {

    private PermissionChecker() {
    }

    public static boolean check(@NotNull Player player, @NotNull String permission) {
        if (permission.isEmpty()) {
            return true;
        }
        if (player.hasPermission(permission)) {
            return true;
        }
        player.sendActionBar(I18n.getComponent("general.no_permission", player));
        return false;
    }

    public static boolean has(@NotNull Player player, @NotNull String permission) {
        return permission.isEmpty() || player.hasPermission(permission);
    }
}