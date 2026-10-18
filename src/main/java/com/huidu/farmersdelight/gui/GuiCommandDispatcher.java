package com.huidu.farmersdelight.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Runs a GUI item's configured command, honouring the [console] / console: prefix and the player placeholder
 * substitution every GUI item supports.
 */
final class GuiCommandDispatcher {

    private GuiCommandDispatcher() {
    }

    static void dispatch(Player player, String configuredCommand) {
        ConfiguredCommand command = parse(configuredCommand, player);
        if (command.command().isEmpty()) {
            return;
        }
        if (command.console()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.command());
        } else {
            Bukkit.dispatchCommand(player, command.command());
        }
    }

    private static final String[] CONSOLE_PREFIXES = {"[console]", "console:"};
    private static final String[] PLAYER_PREFIXES = {"[player]", "player:"};

    private static ConfiguredCommand parse(String configuredCommand, Player player) {
        String command = applyPlaceholders(configuredCommand, player).trim();
        for (String prefix : CONSOLE_PREFIXES) {
            if (startsWithIgnoreCase(command, prefix)) {
                return new ConfiguredCommand(stripSlashes(command.substring(prefix.length()).trim()), true);
            }
        }
        for (String prefix : PLAYER_PREFIXES) {
            if (startsWithIgnoreCase(command, prefix)) {
                return new ConfiguredCommand(stripSlashes(command.substring(prefix.length()).trim()), false);
            }
        }
        return new ConfiguredCommand(stripSlashes(command), false);
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static String stripSlashes(String command) {
        String result = command;
        while (result.startsWith("/")) {
            result = result.substring(1).trim();
        }
        return result;
    }

    private static String applyPlaceholders(String command, Player player) {
        if (command == null) {
            return "";
        }
        return command
                .replace("{player}", player.getName())
                .replace("{player_name}", player.getName())
                .replace("%player%", player.getName())
                .replace("%player_name%", player.getName())
                .replace("{uuid}", player.getUniqueId().toString())
                .replace("{world}", player.getWorld().getName());
    }

    private record ConfiguredCommand(String command, boolean console) {
    }
}
