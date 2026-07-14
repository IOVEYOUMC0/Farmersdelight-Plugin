package com.huidu.farmersdelight.api.buff;

import com.huidu.farmersdelight.manager.BuffBossbarManager;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Per-player buff bossbar display API. Addons (or FD itself) push the live state of an active buff
 * — title, progress 0..1, color, overlay — keyed by a stable NamespacedKey. FD renders all
 * pushed buffs on the admin-configured display channels (boss bar / action bar / tab footer) and
 * boss-bar layout; the addon doesn't choose the channel or layout, the server admin does.
 *
 * <p>Idempotent: calling update repeatedly for the same (player, key) mutates the
 * existing bossbar; pass a fresh title/progress to refresh. Call hide when the buff ends.
 * Player quit and FD disable both flush all bars; addons don't have to clean up on quit.
 *
 * <p>Channels / layout / master toggle live in FD's config.yml under bossbar:. Per-buff
 * enabled flags (e.g. "hide tipsy bar but show raging") are the addon's concern, not FD's.
 */
public final class BuffBossbar {

    private BuffBossbar() {
    }

    /** Master switch — false when FD config has bossbar.enabled: false OR the manager is not
     *  yet initialised. Cheap guard for addons to skip update calls entirely when disabled. */
    public static boolean isEnabled() {
        BuffBossbarManager manager = manager();
        return manager != null && manager.isEnabled();
    }

    /**
     * Create or update the bar for (player, key). No-op when the master toggle is off or the
     * player is offline. Progress is clamped to [0,1].
     *
     * @param owner   the addon plugin pushing the update (logged on errors; future per-plugin features)
     * @param player  the player whose bar to update
     * @param key     a stable identifier; same key across calls updates the same bar
     * @param title   the bar's display name (use Component#translatable so each viewer's client renders in its own locale)
     * @param progress 0..1; clamped automatically
     * @param color   BossBar.Color (PINK, BLUE, RED, GREEN, YELLOW, PURPLE, WHITE)
     * @param overlay BossBar.Overlay (PROGRESS, NOTCHED_6, NOTCHED_10, NOTCHED_12, NOTCHED_20)
     */
    public static void update(Plugin owner, Player player, NamespacedKey key,
                              Component title, float progress,
                              BossBar.Color color, BossBar.Overlay overlay) {
        BuffBossbarManager manager = manager();
        if (manager != null) {
            manager.update(owner, player, key, title, progress, color, overlay);
        }
    }

    /** Remove the bar identified by key from player. Safe to call on a key that
     *  isn't currently shown. */
    public static void hide(Plugin owner, Player player, NamespacedKey key) {
        BuffBossbarManager manager = manager();
        if (manager != null) {
            manager.hide(owner, player, key);
        }
    }

    /** Remove ALL bars an addon registered for player. Call this when the addon stops
     *  caring about the player's state (rarely needed — quit + plugin disable flush automatically). */
    public static void hideAll(Plugin owner, Player player) {
        BuffBossbarManager manager = manager();
        if (manager != null) {
            manager.hideAll(owner, player);
        }
    }

    private static BuffBossbarManager manager() {
        // Single volatile read; null before FD's manager starts and after it stops (same no-op
        // windows as the old getInstance() -> getter chain).
        return BuffBossbarManager.active();
    }

    /** Parse a YAML-friendly color name (case-insensitive, hyphens/underscores OK) to a
     *  BossBar.Color. Returns fallback when raw is null/blank/unknown — lets
     *  config loaders accept any of pink / blue / red / green / yellow / purple / white
     *  without crashing on typos. */
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

    /** Parse a YAML-friendly overlay name (case-insensitive, hyphens/underscores OK) to a
     *  BossBar.Overlay. Returns fallback on unknown — accepts
     *  progress / notched_6 / notched_10 / notched_12 / notched_20. */
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
