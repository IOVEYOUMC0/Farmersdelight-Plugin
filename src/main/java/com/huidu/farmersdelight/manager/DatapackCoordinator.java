package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.advancement.AdvancementDatapackInstaller;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.scheduler.PluginTask;

import java.nio.file.Path;
import java.util.logging.Level;

/** Coordinates asynchronous data-pack file work and the single server data reload it may request. */
public final class DatapackCoordinator {

    private final FarmersDelightPlugin plugin;
    private PluginTask pendingReloadTask;
    private PluginTask pendingSyncRetryTask;
    private volatile boolean syncQueued;
    private volatile boolean removalQueued;
    private String pendingReloadReason;
    private String pendingSyncRetryReason;
    private boolean active = true;

    public DatapackCoordinator(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void queueReload(String reason) {
        if (!active) {
            return;
        }
        pendingReloadReason = reason;
        if (pendingReloadTask != null && !pendingReloadTask.isCancelled()) {
            return;
        }
        pendingReloadTask = plugin.scheduler().runLater(this::runPendingReload, 10L);
    }

    private void runPendingReload() {
        String reloadReason;
        synchronized (this) {
            if (!active) {
                return;
            }
            pendingReloadTask = null;
            reloadReason = pendingReloadReason != null
                    ? pendingReloadReason
                    : I18n.formatConsole("plugin.datapack_reason_apply_advancement_changes");
            pendingReloadReason = null;
        }
        reloadServerDataPacks(reloadReason);
    }

    public synchronized void queueSync(String reason) {
        if (!active) {
            return;
        }
        if (!plugin.isAdvancementsEnabled()) {
            return;
        }
        if (removalQueued) {
            scheduleSyncRetry(reason);
            return;
        }
        if (syncQueued) {
            return;
        }
        org.bukkit.World primaryWorld = plugin.getPrimaryWorld();
        if (primaryWorld == null) {
            return;
        }
        syncQueued = true;
        String worldName = primaryWorld.getName();
        Path datapackRoot = primaryWorld.getWorldFolder().toPath().resolve("datapacks").resolve("advancements");
        plugin.scheduler().runAsync(() -> {
            boolean updated = false;
            try {
                updated = new AdvancementDatapackInstaller(plugin).sync(datapackRoot, worldName);
            } finally {
                syncQueued = false;
            }
            if (updated) {
                queueReload(reason);
            }
        });
    }

    public synchronized void queueRemoval(String reason) {
        if (!active) {
            return;
        }
        if (removalQueued) {
            return;
        }
        org.bukkit.World primaryWorld = plugin.getPrimaryWorld();
        if (primaryWorld == null) {
            return;
        }
        removalQueued = true;
        Path datapackRoot = primaryWorld.getWorldFolder().toPath().resolve("datapacks").resolve("advancements");
        plugin.scheduler().runAsync(() -> {
            boolean removed = false;
            try {
                removed = new AdvancementDatapackInstaller(plugin).remove(datapackRoot);
            } finally {
                removalQueued = false;
            }
            if (removed) {
                queueReload(reason);
            }
        });
    }

    public synchronized void cancelPendingTasks() {
        active = false;
        if (pendingReloadTask != null) {
            pendingReloadTask.cancel();
            pendingReloadTask = null;
        }
        if (pendingSyncRetryTask != null) {
            pendingSyncRetryTask.cancel();
            pendingSyncRetryTask = null;
        }
        pendingReloadReason = null;
        pendingSyncRetryReason = null;
        syncQueued = false;
        removalQueued = false;
    }

    private synchronized void scheduleSyncRetry(String reason) {
        pendingSyncRetryReason = reason;
        if (pendingSyncRetryTask != null && !pendingSyncRetryTask.isCancelled()) {
            return;
        }

        pendingSyncRetryTask = plugin.scheduler().runLater(() -> {
            pendingSyncRetryTask = null;
            String retryReason = pendingSyncRetryReason;
            pendingSyncRetryReason = null;
            if (retryReason != null) {
                queueSync(retryReason);
            }
        }, 20L);
    }

    private void reloadServerDataPacks(String reason) {
        if (plugin.scheduler().isFolia()) {
            I18n.logWarning("plugin.datapack_reload_skipped_folia", "reason", reason);
            return;
        }
        try {
            I18n.logInfo("plugin.datapack_reload", "reason", reason);
            plugin.getServer().reloadData();
            ItemUtils.clearItemCache();
        } catch (UnsupportedOperationException e) {
            I18n.logWarning("plugin.datapack_reload_skipped_unsupported", "reason", reason);
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.WARNING, I18n.formatConsole("plugin.datapack_reload_failed",
                    "reason", reason), throwable);
        }
    }
}
