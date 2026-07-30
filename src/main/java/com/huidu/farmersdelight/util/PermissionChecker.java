package com.huidu.farmersdelight.util;

import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * 集中管理玩家的权限检查。
 * 所有行为类（BlockBehavior）和事件监听器中的权限验证统一通过此类进行，
 * 当玩家无权限时自动发送 ActionBar 提示消息。
 */
public final class PermissionChecker {

    private PermissionChecker() {
    }

    /**
     * 检查玩家是否拥有指定权限，若无权限则发送提示。
     *
     * @param player     目标玩家
     * @param permission 权限节点（如 "farmersdelight.use.cooking_pot"）
     * @return true 表示有权限，false 表示无权限（已发送提示）
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
     * 检查玩家是否拥有指定权限，不发送提示。
     *
     * @param player     目标玩家
     * @param permission 权限节点
     * @return true 表示有权限
     */
    public static boolean has(@NotNull Player player, @NotNull String permission) {
        return permission.isEmpty() || player.hasPermission(permission);
    }
}