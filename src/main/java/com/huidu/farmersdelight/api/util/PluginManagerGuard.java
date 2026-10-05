package com.huidu.farmersdelight.api.util;

import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class PluginManagerGuard implements Listener {

    private static final Set<String> PLUGIN_MANAGER_NAMES = Set.of(
            "plugman", "plm", "pluginmanager", "plugmanx", "plmx", "pluginsmanager", "pl"
    );

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
        // Strip an optional `namespace:` prefix (e.g. `plugman:plm`) so the match is what is after the colon.
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
        sender.sendMessage(I18n.getComponent("command.runtime_management_refused", Map.of("plugin", pluginName)));
    }
}
