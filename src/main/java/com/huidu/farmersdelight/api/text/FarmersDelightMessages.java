package com.huidu.farmersdelight.api.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Map;

public final class FarmersDelightMessages {

    private FarmersDelightMessages() {
    }

    public static void send(Player player, String template) {
        send(player, template, null);
    }

    public static void send(Player player, String template, Map<String, String> placeholders) {
        if (player != null && template != null) {
            player.sendMessage(FarmersDelightText.render(template, player, placeholders));
        }
    }

    public static void actionBar(Player player, String template) {
        actionBar(player, template, null);
    }

    public static void actionBar(Player player, String template, Map<String, String> placeholders) {
        if (player != null && template != null) {
            player.sendActionBar(FarmersDelightText.render(template, player, placeholders));
        }
    }

    public static void title(Player player, String titleTemplate, String subtitleTemplate,
                             int fadeInTicks, int stayTicks, int fadeOutTicks, Map<String, String> placeholders) {
        if (player == null) {
            return;
        }
        Component title = titleTemplate == null
                ? Component.empty()
                : FarmersDelightText.render(titleTemplate, player, placeholders);
        Component subtitle = subtitleTemplate == null
                ? Component.empty()
                : FarmersDelightText.render(subtitleTemplate, player, placeholders);
        Title.Times times = Title.Times.times(ticks(fadeInTicks), ticks(stayTicks), ticks(fadeOutTicks));
        player.showTitle(Title.title(title, subtitle, times));
    }

    private static Duration ticks(int ticks) {
        return Duration.ofMillis(Math.max(0, ticks) * 50L);
    }
}
