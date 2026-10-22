package com.huidu.farmersdelight.advancement;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.advancement.AdvancementAvailability;
import com.huidu.farmersdelight.api.advancement.FarmersDelightAdvancements;
import com.huidu.farmersdelight.i18n.I18n;

/**
 * Owns the advancement system's runtime state: the config switch, the UltimateAdvancementAPI manager and the
 * addon tab registry. The plugin keeps the listener and datapack glue; everything that can be decided by the
 * advancement system itself lives here so the main class does not grow another lifecycle block.
 */
public final class AdvancementSystem {

    // After a rebuild (/ce reload, /fd reload) the immediate advancement re-show can be dropped by a client
    // still applying CraftEngine's resource pack. The delayed force resend is what makes the tab survive it.
    private static final long RESYNC_DELAY_TICKS = 20L;

    private final FarmersDelightPlugin plugin;
    private volatile boolean enabled;
    private AdvancementManager manager;
    private AddonAdvancementRegistry addonRegistry;

    public AdvancementSystem(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    /** The {@code advancements.enabled} config switch, as read by the last config load. */
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public AdvancementManager manager() {
        return manager;
    }

    /** Lazily created: addons may register tabs before the advancement system itself is up. */
    public synchronized AddonAdvancementRegistry addonRegistry() {
        if (addonRegistry == null) {
            addonRegistry = new AddonAdvancementRegistry(plugin);
        }
        return addonRegistry;
    }

    /** Whether the advancement system may run, reporting the one cause worth a log line. */
    public boolean usable() {
        AdvancementAvailability availability = FarmersDelightAdvancements.availability();
        if (availability == AdvancementAvailability.AVAILABLE) {
            return true;
        }
        if (availability == AdvancementAvailability.API_PATCH_MISSING) {
            // Registering a tab needs the layout overload, so an unpatched UltimateAdvancementAPI would only
            // throw NoSuchMethodError and silently drop every tree. Say so and run without them.
            I18n.logSevere("advancement.layout_unsupported");
        }
        return false;
    }

    /**
     * Creates the manager on first use and reloads it on a later pass.
     *
     * @return false when the manager could not be created, so the caller stops setting the system up
     */
    public boolean prepare(boolean reloading) {
        try {
            if (manager == null) {
                manager = new AdvancementManager(plugin);
                manager.load();
            } else if (reloading) {
                manager.reload();
            }
            return true;
        } catch (Throwable failure) {
            // UltimateAdvancementAPI missing at runtime or version incompatible -- run without the system.
            manager = null;
            I18n.logWarning("advancement.award_failed", "id", "init", "error", String.valueOf(failure.getMessage()));
            return false;
        }
    }

    /** Rebuilds addon tabs after the FD tab is up, reloading their definitions when the content changed. */
    public void refreshAddonTabs(boolean reloading) {
        AddonAdvancementRegistry registry = addonRegistry();
        if (!registry.isReady() || reloading) {
            registry.onSystemReady();
        }
    }

    /** Re-shows the FD tab to online players (a no-op on first enable — nobody is online yet). */
    public void resyncOnline() {
        if (manager != null) {
            manager.resyncOnlinePlayers();
        }
    }

    /** Forces a full re-send a short while later, after the client settled on the rebuilt resource pack. */
    public void resyncLater() {
        if (plugin.scheduler() == null) {
            return;
        }
        plugin.scheduler().runLater(() -> {
            if (manager != null) {
                manager.forceResyncOnlinePlayers();
            }
            addonRegistry().forceResyncOnline();
        }, RESYNC_DELAY_TICKS);
    }

    /** Releases the manager on disable, without touching the persistent addon definitions. */
    public void dispose() {
        if (manager != null) {
            manager.dispose();
            manager = null;
        }
    }

    /** Turns the system off: releases the manager and lets addon tabs know the system is down. */
    public void disable() {
        dispose();
        if (addonRegistry != null) {
            addonRegistry.onSystemDown();
        }
    }
}
