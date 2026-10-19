package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.advancement.AdvancementDatapackInstaller;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.api.util.DatapackSupport;
import org.bukkit.World;

import java.nio.file.Path;

/** Cleans legacy advancement files and reports registry changes that need a restart. */
public final class DatapackCoordinator {

    private final FarmersDelightPlugin plugin;
    private volatile boolean removalQueued;
    private boolean restartReported;
    private boolean active = true;

    public DatapackCoordinator(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void queueReload(String reason) {
        if (active && !restartReported) {
            restartReported = true;
            I18n.logWarning("plugin.datapack_pending_restart", "reason", reason);
        }
    }

    public synchronized void queueRemoval(String reason) {
        if (!active) {
            return;
        }
        if (removalQueued) {
            return;
        }
        World primaryWorld = plugin.getPrimaryWorld();
        if (primaryWorld == null) {
            return;
        }
        removalQueued = true;
        Path datapackRoot = DatapackSupport.worldRoot(primaryWorld)
                .resolve("datapacks").resolve("advancements");
        // A refused task never runs, so its own cleanup would never fire either. Release the guard here and
        // leave the removal to the next reason that asks for it, rather than wedging it for the session.
        if (!plugin.scheduler().tryRunAsync(() -> {
            boolean removed;
            try {
                removed = new AdvancementDatapackInstaller().remove(datapackRoot);
            } finally {
                removalQueued = false;
            }
            if (removed) {
                queueReload(reason);
            }
        })) {
            removalQueued = false;
            I18n.logWarning("plugin.datapack_removal_deferred");
        }
    }

    public synchronized void cancelPendingTasks() {
        active = false;
        removalQueued = false;
    }

}
