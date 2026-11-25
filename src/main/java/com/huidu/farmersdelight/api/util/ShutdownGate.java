package com.huidu.farmersdelight.api.util;

import org.bukkit.plugin.Plugin;

import org.jetbrains.annotations.ApiStatus;

/**
 * The one check for work that was queued earlier and replays on a later tick.
 *
 * A deferred task outlives the state it was queued for: a shutdown or a plugin disable landing inside the delay
 * would let the task touch registries that are already gone. On a normal stop CraftEngine disables before this
 * plugin, so a replayed pass over its content reads empty registries and reports every entry as invalid, which
 * floods the console during the last second of a server run with warnings nobody can act on.
 */
@ApiStatus.NonExtendable
public final class ShutdownGate {

    private ShutdownGate() {
    }

    /** Whether the server is stopping or the owning plugin is no longer enabled. */
    public static boolean isClosing(Plugin plugin) {
        return plugin == null || !plugin.isEnabled() || plugin.getServer().isStopping();
    }
}
