package com.huidu.farmersdelight.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.List;

public final class Text {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    // Indexed by legacy color code character (0-9, a-f).
    private static final String[] COLOR_TAGS = {
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple",
            "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple",
            "yellow", "white"
    };

    private Text() {
    }

    public static Component deserialize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        String miniMessage = legacyToMiniMessage(raw);
        try {
            return MINI.deserialize(miniMessage);
        } catch (RuntimeException ex) {
            return Component.text(stripFormatting(raw));
        }
    }

    public static Component name(String raw) {
        return deserialize(raw)
                .colorIfAbsent(NamedTextColor.WHITE)
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static Component lore(String raw) {
        return deserialize(raw)
                .colorIfAbsent(NamedTextColor.GRAY)
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static List<Component> loreLines(List<String> rawLines) {
        List<Component> lines = new ArrayList<>();
        if (rawLines != null) {
            for (String line : rawLines) {
                lines.add(lore(line));
            }
        }
        return lines;
    }

    public static Component title(String raw) {
        return deserialize(raw);
    }

    public static String plain(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        return PLAIN.serialize(deserialize(raw));
    }

    static String legacyToMiniMessage(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        // Fast path: nothing to convert.
        if (input.indexOf('&') < 0 && input.indexOf('§') < 0) {
            return input;
        }

        int length = input.length();
        StringBuilder out = new StringBuilder(length + 16);
        int i = 0;
        while (i < length) {
            char c = input.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < length) {
                char code = input.charAt(i + 1);

                // &#rrggbb hex.
                if (code == '#' && isHex(input, i + 2, 6)) {
                    out.append("<#").append(input, i + 2, i + 8).append('>');
                    i += 8;
                    continue;
                }

                // Bukkit "&x&r&r&g&g&b&b" / "§x§r§r.." expanded hex.
                if ((code == 'x' || code == 'X') && isBukkitHex(input, i)) {
                    out.append("<#");
                    for (int k = 0; k < 6; k++) {
                        out.append(input.charAt(i + 3 + k * 2));
                    }
                    out.append('>');
                    i += 14;
                    continue;
                }

                String tag = codeToTag(code);
                if (tag != null) {
                    out.append(tag);
                    i += 2;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static String codeToTag(char codeRaw) {
        char code = Character.toLowerCase(codeRaw);
        int colorIndex = "0123456789abcdef".indexOf(code);
        if (colorIndex >= 0) {
            return "<" + COLOR_TAGS[colorIndex] + ">";
        }
        return switch (code) {
            case 'k' -> "<obfuscated>";
            case 'l' -> "<bold>";
            case 'm' -> "<strikethrough>";
            case 'n' -> "<underlined>";
            case 'o' -> "<italic>";
            case 'r' -> "<reset>";
            default -> null;
        };
    }

    private static boolean isHex(String s, int offset, int count) {
        if (offset + count > s.length()) {
            return false;
        }
        for (int k = 0; k < count; k++) {
            if (Character.digit(s.charAt(offset + k), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBukkitHex(String s, int i) {
        if (i + 14 > s.length()) {
            return false;
        }
        for (int k = 0; k < 6; k++) {
            char separator = s.charAt(i + 2 + k * 2);
            char hex = s.charAt(i + 3 + k * 2);
            if (separator != '&' && separator != '§') {
                return false;
            }
            if (Character.digit(hex, 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static String stripFormatting(String raw) {
        String withoutTags = raw.replaceAll("<[^>]*>", "");
        return withoutTags.replaceAll("[&§][0-9A-Fa-fK-Ok-oRrXx]", "");
    }
}
