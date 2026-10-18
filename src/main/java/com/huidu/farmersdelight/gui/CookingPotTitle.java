package com.huidu.farmersdelight.gui;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.momirealms.craftengine.bukkit.api.CraftEngineImages;
import net.momirealms.craftengine.core.font.Image;
import net.momirealms.craftengine.core.util.Key;

import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the pot GUI title from the configured title: first the layout tokens, then the shift and image tags
 * inside it.
 */
final class CookingPotTitle {

    private static final Pattern SHIFT_TAG_PATTERN = Pattern.compile("<shift:([+-]?\\d+)>");
    private static final Pattern IMAGE_TAG_PATTERN = Pattern.compile("<image:([a-z0-9_./-]+:[a-z0-9_./-]+)>");
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private CookingPotTitle() {
    }

    static Component resolve(CookingPotBlockBehavior blockBehavior, GuiConfig config, FarmersDelightPlugin plugin) {
        String rawTitle = blockBehavior != null && blockBehavior.getTitleOverride() != null
                ? blockBehavior.getTitleOverride()
                : config.getTitle();
        return MINI_MESSAGE.deserialize(resolveLayout(rawTitle, config, plugin));
    }

    private static String resolveLayout(String rawTitle, GuiConfig config, FarmersDelightPlugin plugin) {
        String title = rawTitle == null ? "Cooking Pot" : rawTitle;
        title = title.replace("<offset>", resolveToken(config.getTitleLayoutOffset(), plugin));
        title = title.replace("<icon>", resolveToken(config.getTitleLayoutIcon(), plugin));
        return resolveToken(title, plugin);
    }

    private static String resolveToken(String value, FarmersDelightPlugin plugin) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return replaceImageTags(replaceShiftTags(value,
                plugin.getCraftEngine().fontManager()::createMiniMessageOffsets));
    }

    // Package-private so the offline test can drive the real loop with a stub offset renderer. A tag whose
    // digits do not fit an int keeps its place in the output while its neighbours are still replaced.
    static String replaceShiftTags(String text, IntFunction<String> offsetRenderer) {
        Matcher matcher = SHIFT_TAG_PATTERN.matcher(text);
        StringBuilder buffer = new StringBuilder();
        while (matcher.find()) {
            Integer offset = parseShiftOffset(matcher.group(1));
            if (offset == null) {
                // Digits that do not fit an int: leave the matched token untouched. Skipping the match
                // without calling appendReplacement is what keeps it in the output — the matcher only
                // copies text up to the position the last replacement consumed.
                continue;
            }
            String replacement = offsetRenderer.apply(offset);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    // The pixel offset a shift tag asks for, or null when the digits do not fit an int. A null result leaves
    // the tag verbatim in the title instead of aborting it: the pattern accepts any number of digits, this runs
    // while a GUI title is built, and an oversized value in gui.yml must not make the pot GUI fail to open.
    static Integer parseShiftOffset(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException outOfIntRange) {
            return null;
        }
    }

    private static String replaceImageTags(String text) {
        Matcher matcher = IMAGE_TAG_PATTERN.matcher(text);
        StringBuilder buffer = new StringBuilder();
        while (matcher.find()) {
            Image image = CraftEngineImages.byId(Key.of(matcher.group(1)));
            String replacement = matcher.group(0);
            if (image != null) {
                replacement = image.miniMessageAt(0, 0);
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }
}
