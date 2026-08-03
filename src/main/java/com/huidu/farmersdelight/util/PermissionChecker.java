package com.huidu.farmersdelight.util;

import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Centralized permission checks for players.
 * All BlockBehavior and listener permission validation routes through this class,
 * which sends an ActionBar denial message when a player lacks permission.
 */
public final class PermissionChecker {

    private PermissionChecker() {
    }

    /**
     * Check if the player has the given permission, sending a denial message if not.
     *
     * player     target player
     * permission the permission node (e.g. farmersdelight.use.cooking_pot)
     * return true if permitted, false if denied (message sent)
     */
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

    /**
     * Check if the player has the given permission without sending a message.
     *
     * player     target player
     * permission the permission node
     * return true if permitted
     */
    public static boolean has(@NotNull Player player, @NotNull String permission) {
        return permission.isEmpty() || player.hasPermission(permission);
    }
}