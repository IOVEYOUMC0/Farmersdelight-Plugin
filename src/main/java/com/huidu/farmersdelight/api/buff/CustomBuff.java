package com.huidu.farmersdelight.api.buff;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.OverrideOnly
public interface CustomBuff {

    String id();

    boolean isActive(Player player);

    void remove(Player player);

    default boolean apply(Player player, int level, int durationSeconds) {
        return false;
    }

    default boolean isLowPriority() {
        return false;
    }

    default int level(Player player) {
        return isActive(player) ? 1 : 0;
    }

    default int remainingSeconds(Player player) {
        return 0;
    }

    default String nameKey() {
        return "";
    }

    default void saveState(Player player) {
    }

    default void restoreState(Player player) {
    }
}
