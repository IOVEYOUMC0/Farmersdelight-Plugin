package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;

/**
 * FarmersDelight hard-depends on CraftEngine for its custom blocks, block entities and scheduler-bound
 * behaviors. If CraftEngine is disabled while the server keeps running, for example a plugin-manager unload
 * or a CraftEngine failure, those references become dead: CraftEngine singletons like BukkitWorldManager go
 * null and FD's tick tasks, chunk-load handlers and behaviors start throwing. There is nothing FD can do
 * without CraftEngine, so it disables itself with a clear message instead of spamming errors.
 *
 * This never fires during a normal server stop, where both plugins are shutting down anyway: the stopping
 * check and the already-disabled check both suppress it.
 */
public final class CraftEngineWatchdogListener implements Listener {

    private static final String CRAFT_ENGINE = "CraftEngine";

    private final FarmersDelightPlugin plugin;

    public CraftEngineWatchdogListener(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        if (!CRAFT_ENGINE.equals(event.getPlugin().getName())) {
            return;
        }
        if (plugin.getServer().isStopping() || !plugin.isEnabled()) {
            return; // normal shutdown, or FD is already going down; nothing to do
        }
        plugin.getLogger().severe(" ");
        plugin.getLogger().severe("==================================================================");
        plugin.getLogger().severe(" CraftEngine was disabled while the server is running.");
        plugin.getLogger().severe(" FarmersDelight cannot function without it, so it is disabling");
        plugin.getLogger().severe(" itself now. Restart the server (/stop) to bring both back up.");
        plugin.getLogger().severe("==================================================================");
        plugin.getLogger().severe(" ");
        plugin.getServer().getPluginManager().disablePlugin(plugin);
    }
}
