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

    /**
     * Whether content functions restricted to harmful effects may select this buff. A buff that only helps the
     * player leaves this false, which is the default.
     */
    default boolean isHarmful() {
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
