package com.huidu.farmersdelight.api.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.Locale;
import java.util.Set;

/**
 * Cancels plugin-manager (PlugMan / PlugManX / PluginManager / ...) commands that try to enable,
 * disable, load, unload or reload a registered plugin name at runtime. Tells the sender the plugin
 * does not support live management and to /stop + restart instead.
 *
 * <p>This addresses the failure mode where CraftEngine + per-chunk block entities + scheduler tasks
 * keep references into the plugin's classloader: once the classloader closes, every late-bound
 * lambda / event delivery throws NoClassDefFoundError. Detecting and refusing the command
 * up front is cleaner than waiting for onDisable to scream a warning after the damage is
 * already in flight.
 *
 * <p>Vanilla /reload bypasses this guard (it's a core command, not a plugin command); the
 * plugin's onEnable should still install a JVM-lifetime reload guard (e.g. a system property) so
 * the second onEnable call self-disables.
 *
 * <p>Register one instance per plugin with the exact plugin name as it appears in plugin.yml.
 */
public final class PluginManagerGuard implements Listener {

    /** Known plugin-manager command names (with and without their command-block namespace prefix). */
    private static final Set<String> PLUGIN_MANAGER_NAMES = Set.of(
            "plugman", "plm", "pluginmanager", "plugmanx", "plmx", "pluginsmanager", "pl"
    );

    /** Verbs that mutate plugin state. info, list, etc. are allowed through. */
    private static final Set<String> DESTRUCTIVE_ACTIONS = Set.of(
            "unload", "reload", "disable", "enable", "load", "restart", "stop", "start"
    );

    private final String pluginName;
    private final String pluginNameLower;

    public PluginManagerGuard(String pluginName) {
        this.pluginName = pluginName;
        this.pluginNameLower = pluginName.toLowerCase(Locale.ROOT);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (matches(event.getMessage())) {
            event.setCancelled(true);
            sendRefusal(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        if (matches("/" + event.getCommand())) {
            event.setCancelled(true);
            sendRefusal(event.getSender());
        }
    }

    private boolean matches(String message) {
        if (message == null || message.isEmpty()) return false;
        String trimmed = message.startsWith("/") ? message.substring(1) : message;
        String[] parts = trimmed.trim().toLowerCase(Locale.ROOT).split("\\s+");
        if (parts.length < 3) return false;
        // Strip an optional `namespace:` prefix (e.g. `plugman:plm`) so we match what's after the colon.
        String cmd = parts[0];
        int colon = cmd.indexOf(':');
        if (colon >= 0) cmd = cmd.substring(colon + 1);
        if (!PLUGIN_MANAGER_NAMES.contains(cmd)) return false;
        if (!DESTRUCTIVE_ACTIONS.contains(parts[1])) return false;
        for (int i = 2; i < parts.length; i++) {
            if (parts[i].equals(pluginNameLower)) return true;
        }
        return false;
    }

    private void sendRefusal(CommandSender sender) {
        sender.sendMessage(Component.text()
                .append(Component.text("[" + pluginName + "] ").color(NamedTextColor.YELLOW))
                .append(Component.text(
                        "This plugin does not support runtime management. /stop and restart the server to apply changes."
                ).color(NamedTextColor.RED))
                .build());
    }
}
