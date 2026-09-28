package com.huidu.farmersdelight.compat;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.buff.CustomBuff;
import com.huidu.farmersdelight.api.buff.CustomBuffRegistry;
import com.huidu.farmersdelight.api.text.FarmersDelightText;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Optional;

public final class PlaceholderApiHook extends PlaceholderExpansion {

    private final FarmersDelightPlugin plugin;

    public PlaceholderApiHook(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "farmersdelight";
    }

    @Override
    @SuppressWarnings("UnstableApiUsage")
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getPluginMeta().getAuthors());
    }

    @Override
    @SuppressWarnings("UnstableApiUsage")
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(@Nullable OfflinePlayer offline, @NotNull String params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        String key = params.toLowerCase(Locale.ROOT);
        if ("buff_count".equals(key)) {
            return Integer.toString(activeBuffCount(offline));
        }
        if (key.startsWith("buff_")) {
            return resolveBuff(offline, key.substring("buff_".length()));
        }
        return null;
    }

    private int activeBuffCount(@Nullable OfflinePlayer offline) {
        return onlinePlayer(offline)
                .map(player -> {
                    int count = 0;
                    for (CustomBuff buff : CustomBuffRegistry.all()) {
                        try {
                            if (buff.isActive(player)) count++;
                        } catch (RuntimeException ignored) {
                        }
                    }
                    return count;
                })
                .orElse(0);
    }

    private String resolveBuff(@Nullable OfflinePlayer offline, String rest) {
        int lastUnderscore = rest.lastIndexOf('_');
        if (lastUnderscore <= 0) return "";

        // time_fmt is the only compound field — peel it off before splitting the id.
        String idPart;
        String field;
        if (rest.endsWith("_time_fmt")) {
            field = "time_fmt";
            idPart = rest.substring(0, rest.length() - "_time_fmt".length());
        } else {
            field = rest.substring(lastUnderscore + 1);
            idPart = rest.substring(0, lastUnderscore);
        }
        if (idPart.isEmpty()) return "";

        // <ns>_<id> -> <ns>:<id>. Use the FIRST underscore as the namespace separator since custom buff
        // paths can themselves contain underscores (e.g. brewinandchewin:sweet_heart).
        int firstUnderscore = idPart.indexOf('_');
        if (firstUnderscore <= 0) return "";
        String buffId = idPart.substring(0, firstUnderscore) + ":" + idPart.substring(firstUnderscore + 1);

        CustomBuff buff = findById(buffId);
        if (buff == null) return "";

        return onlinePlayer(offline)
                .map(player -> switch (field) {
                    case "active" -> buff.isActive(player) ? "1" : "0";
                    case "level" -> Integer.toString(buff.level(player));
                    case "time" -> Integer.toString(buff.remainingSeconds(player));
                    case "time_fmt" -> FarmersDelightText.formatDuration(buff.remainingSeconds(player));
                    case "name" -> resolveName(buff);
                    default -> "";
                })
                .orElseGet(() -> inactiveDefault(field, buff));
    }

    private static String inactiveDefault(String field, CustomBuff buff) {
        return switch (field) {
            case "active", "level", "time" -> "0";
            case "time_fmt" -> "0:00";
            case "name" -> resolveName(buff);
            default -> "";
        };
    }

    private static String resolveName(CustomBuff buff) {
        String key = buff.nameKey();
        if (key == null || key.isEmpty()) return buff.id();
        return FarmersDelightText.serverText(key);
    }

    private static CustomBuff findById(String id) {
        return CustomBuffRegistry.byId(id);
    }

    private static Optional<Player> onlinePlayer(@Nullable OfflinePlayer offline) {
        if (offline == null) return Optional.empty();
        if (offline instanceof Player p && p.isOnline()) return Optional.of(p);
        Player p = Bukkit.getPlayer(offline.getUniqueId());
        return (p != null && p.isOnline()) ? Optional.of(p) : Optional.empty();
    }
}
