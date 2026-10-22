package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.buff.CustomBuffRegistry;
import com.huidu.farmersdelight.api.util.ShutdownBudget;
import com.huidu.farmersdelight.effect.EffectManager;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.scheduler.SchedulerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Owns the buff system's lifecycle: the {@code buff.enabled} switch, the bossbar manager and the online-player
 * buff flush that runs before the effect listener is torn down. The plugin keeps only the enable/disable call
 * sites and the config read.
 */
public final class BuffSystem {

    private static final int DEFAULT_SHUTDOWN_WAIT_MILLIS = 5000;

    private final FarmersDelightPlugin plugin;
    private volatile boolean enabled = true;
    private BuffBossbarManager bossbarManager;
    // Set by flushBeforeRuntimeDisable(): the buff/bossbar state is already persisted, so the budgeted onDisable
    // steps must not repeat the work (and on Folia must not try the now-refused schedulers).
    private volatile boolean runtimeFlushDone;

    public BuffSystem(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        // Mirror the switch into the addon-facing registry so its entry points can degrade to no-ops without
        // reaching back through the plugin singleton from an addon thread.
        CustomBuffRegistry.setSystemEnabled(enabled);
    }

    /** Null-tolerant accessor for reload paths that run before the manager exists (or after it is gone). */
    public BuffBossbarManager bossbarManagerOrNull() {
        return bossbarManager;
    }

    public void start(Consumer<BuffBossbarManager> registerVisualHandlers) {
        bossbarManager = new BuffBossbarManager(plugin);
        bossbarManager.applyConfig(plugin.getFirstConfigSection("buff.display", "bossbar"), enabled);
        EffectManager.applyBossbarStyles(plugin.getFirstConfigSection("buff.display.styles", "bossbar.styles"));
        bossbarManager.start();
        registerVisualHandlers.accept(bossbarManager);
    }

    /** Stops the bossbar task; idempotent, and skips the work already done by the runtime flush. */
    public void stop() {
        if (bossbarManager == null) {
            return;
        }
        if (!runtimeFlushDone) {
            bossbarManager.stop();
        }
        bossbarManager = null;
    }

    /**
     * Persists every online player's buff state before the effect listener stops.
     *
     * @param budget the disable budget to wait within, or null for the fixed default
     */
    public void saveOnlinePlayerBuffs(ShutdownBudget budget) {
        if (runtimeFlushDone) {
            return;
        }
        List<Player> players = List.copyOf(plugin.getServer().getOnlinePlayers());
        if (players.isEmpty()) {
            return;
        }
        SchedulerAdapter currentScheduler = plugin.scheduler();
        if (currentScheduler == null) {
            for (Player player : players) {
                try {
                    CustomBuffRegistry.saveAll(player);
                } catch (Throwable ignored) {
                    // Best effort fallback when enable failed before the scheduler was created.
                }
            }
            return;
        }
        CountDownLatch pending = new CountDownLatch(players.size());
        int skipped = 0;
        for (Player player : players) {
            if (Bukkit.isOwnedByCurrentRegion(player)) {
                // The caller already owns this player's region (Paper always reports owned, and on Folia a
                // flush started from a region thread owns that region's players). Saving inline here keeps
                // the latch wait below from depending on a task queued onto the very thread that waits.
                try {
                    CustomBuffRegistry.saveAll(player);
                } catch (Throwable ignored) {
                    // Per-player isolation; a broken addon must not prevent other saves.
                } finally {
                    pending.countDown();
                }
                continue;
            }
            try {
                currentScheduler.runForEntity(player, () -> {
                    try {
                        CustomBuffRegistry.saveAll(player);
                    } catch (Throwable ignored) {
                        // Per-player isolation; a broken addon must not prevent other saves.
                    } finally {
                        pending.countDown();
                    }
                }, pending::countDown);
            } catch (Throwable rejected) {
                // Folia marks the plugin disabled before onDisable and its schedulers then refuse every task.
                // Count the miss and report it: a silent skip loses that player's buff state.
                skipped++;
                pending.countDown();
            }
        }
        long waitMillis = budget == null ? DEFAULT_SHUTDOWN_WAIT_MILLIS : budget.remainingMillis();
        try {
            if (!pending.await(Math.max(1L, waitMillis), TimeUnit.MILLISECONDS)) {
                plugin.getLogger().warning(I18n.formatConsole("plugin.disable_step_save_player_buffs_timeout"));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (skipped > 0) {
            plugin.getLogger().warning(I18n.formatConsole(
                    "plugin.disable_step_save_player_buffs_skipped", "count", String.valueOf(skipped)));
        }
    }

    /** Persists the buff/bossbar state that needs a live scheduler while the plugin is still enabled. */
    public void flushBeforeRuntimeDisable(ShutdownBudget budget) {
        if (runtimeFlushDone) {
            return;
        }
        saveOnlinePlayerBuffs(budget);
        if (bossbarManager != null) {
            bossbarManager.stop();
        }
        runtimeFlushDone = true;
    }
}
