package com.huidu.farmersdelight.api.util;

import com.huidu.farmersdelight.api.FarmersDelightApi;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Disables an addon when one of its hard dependencies is disabled at runtime, before the addon's tasks or
 * listeners can call into a dead class loader. This is the shared replacement for the per-addon watchdog
 * listeners; registration stays in the addon, which decides when it is enabled.
 *
 * Normal shutdown and an already-disabling addon are ignored, so the watchdog never interferes with a clean
 * server stop or with the addon's own disable cascade.
 */
public final class RequiredPluginWatchdog implements Listener {

    private static final Set<String> DEFAULT_REQUIRED = Set.of("FarmersDelight", "CraftEngine");

    private final JavaPlugin addon;
    private final Set<String> required;

    /** Watches FarmersDelight and CraftEngine, the hard dependencies of every family addon. */
    public RequiredPluginWatchdog(JavaPlugin addon) {
        this(addon, DEFAULT_REQUIRED.toArray(String[]::new));
    }

    /**
     * Watches the named plugin(s); names are matched exactly against the runtime plugin name. At least one
     * name is required.
     */
    public RequiredPluginWatchdog(JavaPlugin addon, String... requiredNames) {
        if (addon == null) {
            throw new NullPointerException("addon");
        }
        if (requiredNames == null || requiredNames.length == 0) {
            throw new IllegalArgumentException("at least one required plugin name is needed");
        }
        Set<String> names = new LinkedHashSet<>();
        for (String name : requiredNames) {
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
        }
        if (names.isEmpty()) {
            throw new IllegalArgumentException("required plugin names cannot be blank");
        }
        this.addon = addon;
        this.required = Collections.unmodifiableSet(names);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        if (event == null || event.getPlugin() == null || !requires(required, event.getPlugin().getName())) {
            return;
        }
        if (ShutdownGate.isClosing(addon)) {
            return; // normal shutdown, or this addon is already going down
        }
        addon.getLogger().severe(FarmersDelightApi.consoleMessage("plugin.missing_dependency",
                "name", addon.getName(), "dependency", event.getPlugin().getName()));
        addon.getServer().getPluginManager().disablePlugin(addon);
    }

    static boolean requires(Set<String> required, String pluginName) {
        return required != null && pluginName != null && required.contains(pluginName);
    }
}
