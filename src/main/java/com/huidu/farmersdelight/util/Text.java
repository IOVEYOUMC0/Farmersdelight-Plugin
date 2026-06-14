package com.huidu.farmersdelight.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.List;

/**
 * 插件向玩家展示的所有内容的中央文本渲染器：GUI 物品名称、GUI 物品 lore、
 * 聊天消息以及动作栏。
 *
 * <p>单个字符串可以自由混用 <b>MiniMessage</b> 标签（<green>、<#ff8800>、
 * <gradient:..>、<bold>……）以及传统的 &/§ 颜色代码（包括
 * &#rrggbb 和 Bukkit 的 §x§r§r.. 十六进制）。传统代码会被转换为 MiniMessage，
 * 整个字符串由 MiniMessage 解析一次，因此旧配置仍可正常工作，新的 MiniMessage
 * 配置也能渲染。</p>
 *
 * <p>name(String) 和 lore(String) 还额外修复了 NBT 驱动文本长期存在的两个
 * 视觉问题：</p>
 * <ul>
 *   <li><b>斜体</b> &mdash; 自定义物品名称和 lore 默认以斜体渲染。这些辅助方法
 *       会禁用斜体，除非文本明确要求斜体。</li>
 *   <li><b>暗紫色 lore</b> &mdash; 没有颜色的 lore 行会回退到原版的
 *       dark_purple 默认值。lore(String) 提供 gray（而
 *       name(String) 提供 white），仅在文本自身未设置颜色时生效。</li>
 * </ul>
 */
public final class Text {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    // 以传统颜色代码字符（0-9、a-f）作为索引。
    private static final String[] COLOR_TAGS = {
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple",
            "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple",
            "yellow", "white"
    };

    private Text() {
    }

    /**
     * 将 MiniMessage + 传统颜色代码解析为 Component。永不抛出异常：格式错误的输入会回退
     * 到纯（无样式）文本，因此一行错误的配置永远不会破坏 GUI 渲染或消息。
     */
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

    /**
     * 渲染物品显示名称：解析后的文本，强制关闭斜体，当文本自身未设置颜色时
     * 默认为白色。
     */
    public static Component name(String raw) {
        return deserialize(raw)
                .colorIfAbsent(NamedTextColor.WHITE)
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /**
     * 渲染物品 lore 行：解析后的文本，强制关闭斜体，当文本自身未设置颜色时
     * 默认为灰色（绝不会是原版暗紫色 lore 默认值）。
     */
    public static Component lore(String raw) {
        return deserialize(raw)
                .colorIfAbsent(NamedTextColor.GRAY)
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /** 用于整块 lore 的便捷方法。 */
    public static List<Component> loreLines(List<String> rawLines) {
        List<Component> lines = new ArrayList<>();
        if (rawLines != null) {
            for (String line : rawLines) {
                lines.add(lore(line));
            }
        }
        return lines;
    }

    /** 渲染物品栏/菜单标题（MiniMessage + 传统代码，不做斜体/颜色默认处理）。 */
    public static Component title(String raw) {
        return deserialize(raw);
    }

    /** 对已构建的 Component 强制关闭斜体，除非它显式设置了斜体。 */
    public static Component noItalic(Component component) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /** 去除所有格式并返回纯文本（用于控制台输出和比较）。 */
    public static String plain(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        return PLAIN.serialize(deserialize(raw));
    }

    /**
     * 将传统的 &/§ 颜色代码（包括 &#rrggbb 和 Bukkit 的
     * §x§r§r§g§g§b§b 十六进制）转换为 MiniMessage 标签，同时保持任何已有的 MiniMessage 标签和
     * 所有其他文本不变。设为包级私有以便直接进行单元测试。
     */
    static String legacyToMiniMessage(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        // 快速路径：没有需要转换的内容。
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

                // &#rrggbb 十六进制。
                if (code == '#' && isHex(input, i + 2, 6)) {
                    out.append("<#").append(input, i + 2, i + 8).append('>');
                    i += 8;
                    continue;
                }

                // Bukkit "&x&r&r&g&g&b&b" / "§x§r§r.." 展开的十六进制。
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

    /**
     * 检查从 i 开始的 Bukkit 展开十六进制序列，其中 s.charAt(i) 是
     * 颜色字符，s.charAt(i + 1) 是 x/X：随后跟着六个
     * <colourChar><hexDigit> 对（总共 14 个字符）。
     */
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
