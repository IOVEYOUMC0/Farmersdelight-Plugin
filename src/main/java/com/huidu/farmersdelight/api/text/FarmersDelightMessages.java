package com.huidu.farmersdelight.api.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Map;

/**
 * Stable, addon-facing player message helpers. Each takes a template rendered through
 * FarmersDelightText#render (string placeholders, <l10n:> translation tags resolved for the
 * player, CraftEngine glyphs, MiniMessage + legacy colors), then sends it as chat, action bar, or title.
 *
 * Threading: these touch the player, so call them on the player's owning thread (the main thread on Paper, the
 * player's region thread on Folia), not from an async task.
 */
public final class FarmersDelightMessages {

    private FarmersDelightMessages() {
    }

    /** Sends a chat message rendered from template. */
    public static void send(Player player, String template) {
        send(player, template, null);
    }

    /** Sends a chat message rendered from template with {key} placeholders. */
    public static void send(Player player, String template, Map<String, String> placeholders) {
        if (player != null && template != null) {
            player.sendMessage(FarmersDelightText.render(template, player, placeholders));
        }
    }

    /** Sends an action-bar message rendered from template. */
    public static void actionBar(Player player, String template) {
        actionBar(player, template, null);
    }

    /** Sends an action-bar message rendered from template with {key} placeholders. */
    public static void actionBar(Player player, String template, Map<String, String> placeholders) {
        if (player != null && template != null) {
            player.sendActionBar(FarmersDelightText.render(template, player, placeholders));
        }
    }

    /**
     * Shows a title/subtitle rendered from the given templates (either may be null for an empty line), with
     * fade-in/stay/fade-out durations in ticks.
     */
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
