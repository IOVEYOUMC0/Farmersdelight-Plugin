package com.huidu.farmersdelight.util;

import net.kyori.adventure.text.Component;
import net.momirealms.craftengine.bukkit.api.CraftEngineImages;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.core.font.Image;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a template string into an Adventure Component: substitutes string placeholders, resolves
 * <l10n:>/<lang:> translation tags for a viewer, resolves CraftEngine <image:ns:id>
 * and <shift:N> glyph tags into image-font output, then parses MiniMessage + legacy color codes.
 * Component placeholders (e.g. an item display name) can be spliced in directly.
 */
public final class PresentationUtils {

    private static final Pattern SHIFT_TAG = Pattern.compile("<shift:(-?\\d+)>");
    private static final Pattern IMAGE_TAG = Pattern.compile("<image:([a-z0-9_./-]+:[a-z0-9_./-]+)>");

    private PresentationUtils() {
    }

    public static Component render(String template, Player viewer,
                                  Map<String, String> placeholders, Map<String, Component> components) {
        if (template == null) {
            return Component.empty();
        }
        String text = template;
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                text = text.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
            }
        }
        text = ItemUtils.resolveTranslationTags(text, viewer);
        text = resolveGlyphTags(text);
        if (components == null || components.isEmpty()) {
            return Text.deserialize(text);
        }
        return spliceComponents(text, components);
    }

    /** Resolves CraftEngine <shift:N> and <image:ns:id> tags into image-font MiniMessage output. */
    public static String resolveGlyphTags(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        return replaceImageTags(replaceShiftTags(input));
    }

    /** A single horizontal pixel-shift glyph (CraftEngine offset font), or empty when unavailable. */
    public static String shift(int pixels) {
        try {
            BukkitCraftEngine ce = BukkitCraftEngine.instance();
            if (ce != null) {
                return ce.fontManager().createMiniMessageOffsets(pixels);
            }
        } catch (Throwable ignored) {
            // CraftEngine font manager unavailable
        }
        return "";
    }

    /** The image-font MiniMessage output for a CraftEngine image glyph id, or empty when unresolved. */
    public static String imageGlyph(String glyphId) {
        if (glyphId == null) {
            return "";
        }
        try {
            Image image = CraftEngineImages.byId(Key.of(glyphId));
            if (image != null) {
                return image.miniMessageAt(0, 0);
            }
        } catch (Throwable ignored) {
            // image not loaded / API unavailable
        }
        return "";
    }

    private static String replaceShiftTags(String text) {
        Matcher matcher = SHIFT_TAG.matcher(text);
        StringBuilder buffer = new StringBuilder();
        while (matcher.find()) {
            String replacement;
            try {
                replacement = shift(Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException e) {
                replacement = "";
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String replaceImageTags(String text) {
        Matcher matcher = IMAGE_TAG.matcher(text);
        StringBuilder buffer = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(imageGlyph(matcher.group(1))));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    /** Parses text as MiniMessage, splicing each {key} occurrence with its Component value. */
    private static Component spliceComponents(String text, Map<String, Component> components) {
        Component out = Component.empty();
        int i = 0;
        int len = text.length();
        while (i < len) {
            int bestIdx = -1;
            String bestKey = null;
            for (String key : components.keySet()) {
                int idx = text.indexOf("{" + key + "}", i);
                if (idx >= 0 && (bestIdx < 0 || idx < bestIdx)) {
                    bestIdx = idx;
                    bestKey = key;
                }
            }
            if (bestIdx < 0 || bestKey == null) {
                out = out.append(Text.deserialize(text.substring(i)));
                break;
            }
            if (bestIdx > i) {
                out = out.append(Text.deserialize(text.substring(i, bestIdx)));
            }
            Component replacement = components.get(bestKey);
            if (replacement != null) {
                out = out.append(replacement);
            }
            i = bestIdx + bestKey.length() + 2;
        }
        return out;
    }
}
