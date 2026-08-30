package com.huidu.farmersdelight.api.text;

import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.PresentationUtils;
import com.huidu.farmersdelight.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class FarmersDelightText {

    private FarmersDelightText() {
    }

    public static Component render(String template, Player viewer) {
        return PresentationUtils.render(template, viewer, null, null);
    }

    public static Component render(String template, Player viewer, Map<String, String> placeholders) {
        return PresentationUtils.render(template, viewer, placeholders, null);
    }

    public static Component render(String template, Player viewer,
                                   Map<String, String> placeholders, Map<String, Component> components) {
        return PresentationUtils.render(template, viewer, placeholders, components);
    }

    public static List<Component> buildLore(List<String> templates, Player viewer, Map<String, String> placeholders) {
        List<Component> lore = new ArrayList<>();
        if (templates != null) {
            for (String template : templates) {
                lore.add(render(template, viewer, placeholders).decoration(TextDecoration.ITALIC, false));
            }
        }
        return lore;
    }

    public static String resolveGlyphs(String text) {
        return PresentationUtils.resolveGlyphTags(text);
    }

    // Parsed Components are immutable and cached in Text, so addons parsing the same fixed template
    // repeatedly (GUI titles, lore, display names) reuse the parse result instead of re-parsing.
    public static Component deserialize(String raw) {
        return Text.deserialize(raw);
    }

    public static Component deserializeGlyphs(String raw) {
        return Text.deserialize(PresentationUtils.resolveGlyphTags(raw));
    }

    public static Component glyph(String glyphId) {
        return Text.deserialize(PresentationUtils.imageGlyph(glyphId));
    }

    public static String shift(int pixels) {
        return PresentationUtils.shift(pixels);
    }

    public static String serverText(String key) {
        return I18n.serverText(key);
    }

    public static Component serverComponent(String key, Object... args) {
        return I18n.serverComponent(key, args);
    }

    public static Component translatable(String key, Object... args) {
        return I18n.translatableWithFallback(key, args);
    }

    public static String formatDuration(int seconds) {
        if (seconds < 0) seconds = 0;
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        int secs = seconds % 60;
        StringBuilder sb = new StringBuilder(8);
        if (hours > 0) {
            sb.append(hours).append(':');
            if (minutes < 10) sb.append('0');
            sb.append(minutes);
        } else {
            sb.append(minutes);
        }
        sb.append(':');
        if (secs < 10) sb.append('0');
        sb.append(secs);
        return sb.toString();
    }
}
