package com.huidu.farmersdelight.command;

import com.huidu.farmersdelight.i18n.I18n;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class CommandSupport {

    static final MiniMessage MINI = MiniMessage.miniMessage();
    static final String BASE_PERMISSION = "farmersdelight.command";
    static final String ADMIN_PERMISSION = "farmersdelight.admin";
    static final String DISCOVERY_PERMISSION = "farmersdelight.command.recipe.discovery";

    private CommandSupport() {
    }

    static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    static List<String> prefixFilter(String partial, List<String> options) {
        List<String> completions = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(partial)) {
                completions.add(option);
            }
        }
        return completions;
    }

    static List<String> onlinePlayerNames() {
        List<String> names = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            names.add(online.getName());
        }
        return names;
    }

    static boolean isInteger(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    static int parsePositiveInt(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static void sendNoPermission(CommandSender sender) {
        if (sender instanceof Player player) {
            sender.sendMessage(I18n.getComponent("general.no_permission", player));
        } else {
            sender.sendMessage(I18n.getComponent("general.no_permission"));
        }
    }
}
