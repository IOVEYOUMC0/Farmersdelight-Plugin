package com.huidu.farmersdelight.api.buff;

import com.huidu.farmersdelight.manager.BuffBossbarManager;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.NonExtendable
public final class BuffBossbar {

    private BuffBossbar() {
    }

    public static boolean isEnabled() {
        BuffBossbarManager manager = manager();
        return manager != null && manager.isEnabled();
    }

    public static void update(Plugin owner, Player player, NamespacedKey key,
                              Component title, float progress,
                              BossBar.Color color, BossBar.Overlay overlay) {
        BuffBossbarManager manager = manager();
        if (manager != null) {
            manager.update(owner, player, key, title, progress, color, overlay);
        }
    }

    public static void hide(Plugin owner, Player player, NamespacedKey key) {
        BuffBossbarManager manager = manager();
        if (manager != null) {
            manager.hide(owner, player, key);
        }
    }

    public static void hideAll(Plugin owner, Player player) {
        BuffBossbarManager manager = manager();
        if (manager != null) {
            manager.hideAll(owner, player);
        }
    }

    private static BuffBossbarManager manager() {
        // Single volatile read; null before the manager starts and after it stops.
        return BuffBossbarManager.active();
    }

    public static BossBar.Color parseColor(String raw, BossBar.Color fallback) {
        if (raw == null) return fallback;
        String norm = raw.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
        if (norm.isEmpty()) return fallback;
        try {
            return BossBar.Color.valueOf(norm);
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    public static BossBar.Overlay parseOverlay(String raw, BossBar.Overlay fallback) {
        if (raw == null) return fallback;
        String norm = raw.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
        if (norm.isEmpty()) return fallback;
        try {
            return BossBar.Overlay.valueOf(norm);
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }
}
