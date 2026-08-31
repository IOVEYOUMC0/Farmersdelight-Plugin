package com.huidu.farmersdelight.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    // MiniMessage may return styled text as the root node, or wrap it in an empty root node; so assertions
    // check the effective (inherited) style of the first non-empty text leaf, making the test hold either way.
    private static TextColor effectiveColor(Component component) {
        return effectiveColor(component, null);
    }

    private static TextColor effectiveColor(Component component, TextColor inherited) {
        TextColor here = component.color() != null ? component.color() : inherited;
        if (component instanceof TextComponent text && !text.content().isEmpty()) {
            return here;
        }
        for (Component child : component.children()) {
            TextColor resolved = effectiveColor(child, here);
            if (resolved != null) {
                return resolved;
            }
        }
        return here;
    }

    private static TextDecoration.State effectiveItalic(Component component) {
        return effectiveItalic(component, TextDecoration.State.NOT_SET);
    }

    private static TextDecoration.State effectiveItalic(Component component, TextDecoration.State inherited) {
        TextDecoration.State here = component.decoration(TextDecoration.ITALIC);
        if (here == TextDecoration.State.NOT_SET) {
            here = inherited;
        }
        if (component instanceof TextComponent text && !text.content().isEmpty()) {
            return here;
        }
        for (Component child : component.children()) {
            TextDecoration.State resolved = effectiveItalic(child, here);
            if (resolved != TextDecoration.State.NOT_SET) {
                return resolved;
            }
        }
        return here;
    }

    // ---- legacy -> MiniMessage conversion ----

    @Test
    void convertsBasicColorCodes() {
        assertEquals("<green>Green", Text.legacyToMiniMessage("&aGreen"));
        assertEquals("<red>Red", Text.legacyToMiniMessage("&cRed"));
        assertEquals("<gray>Gray <white>White", Text.legacyToMiniMessage("&7Gray &fWhite"));
        assertEquals("<black><dark_blue><dark_green><dark_aqua><dark_red><dark_purple>",
                Text.legacyToMiniMessage("&0&1&2&3&4&5"));
        assertEquals("<gold><gray><dark_gray><blue><green><aqua><red><light_purple><yellow><white>",
                Text.legacyToMiniMessage("&6&7&8&9&a&b&c&d&e&f"));
    }

    @Test
    void convertsFormatCodes() {
        assertEquals("<obfuscated><bold><strikethrough><underlined><italic><reset>",
                Text.legacyToMiniMessage("&k&l&m&n&o&r"));
    }

    @Test
    void convertsSectionSignCodesToo() {
        assertEquals("<red>Hi", Text.legacyToMiniMessage("§cHi"));
    }

    @Test
    void isCaseInsensitiveForCodes() {
        assertEquals("<green><bold>X", Text.legacyToMiniMessage("&A&LX"));
    }

    @Test
    void convertsAmpersandHex() {
        assertEquals("<#ff8800>Hex", Text.legacyToMiniMessage("&#ff8800Hex"));
        assertEquals("<#FF8800>Hex", Text.legacyToMiniMessage("&#FF8800Hex"));
    }

    @Test
    void convertsBukkitExpandedHex() {
        assertEquals("<#ff8800>Hex", Text.legacyToMiniMessage("§x§f§f§8§8§0§0Hex"));
        assertEquals("<#ff8800>Hex", Text.legacyToMiniMessage("&x&f&f&8&8&0&0Hex"));
    }

    @Test
    void leavesPlainTextAndLooseAmpersandsUntouched() {
        assertEquals("No codes here", Text.legacyToMiniMessage("No codes here"));
        // A '&' not followed by a valid code character is left unchanged.
        assertEquals("Tom & Jerry", Text.legacyToMiniMessage("Tom & Jerry"));
        assertEquals("AT&T", Text.legacyToMiniMessage("AT&T"));
        // 'z' and 'g' are not valid legacy codes.
        assertEquals("&z&g", Text.legacyToMiniMessage("&z&g"));
        // Incomplete hex sequences are kept as-is (too few digits).
        assertEquals("&#ff88", Text.legacyToMiniMessage("&#ff88"));
    }

    @Test
    void leavesExistingMiniMessageTagsUntouched() {
        assertEquals("<gradient:gold:yellow>Hi</gradient>",
                Text.legacyToMiniMessage("<gradient:gold:yellow>Hi</gradient>"));
        // Mixed legacy + MiniMessage in the same string.
        assertEquals("<gray>Hi <green>MM", Text.legacyToMiniMessage("&7Hi <green>MM"));
    }

    // ---- deserialize ----

    @Test
    void deserializeParsesLegacyAndMiniMessage() {
        assertEquals("Green", PLAIN.serialize(Text.deserialize("&aGreen")));
        assertEquals("Red", PLAIN.serialize(Text.deserialize("<red>Red")));
        assertEquals("Hi MM", PLAIN.serialize(Text.deserialize("&7Hi <green>MM")));
    }

    @Test
    void deserializeSupportsTranslatableArguments() {
        Component component = Text.deserialize("<gray><lang:'test.key':'<white>12'>");
        TranslatableComponent translatable = component.children().stream()
                .filter(TranslatableComponent.class::isInstance)
                .map(TranslatableComponent.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals("test.key", translatable.key());
        assertEquals("12", PLAIN.serialize(translatable.arguments().getFirst().asComponent()));

        assertTrue(Text.deserialize("<lang:test.key>").children().stream()
                .anyMatch(TranslatableComponent.class::isInstance));
    }

    @Test
    void deserializeHandlesNullAndEmpty() {
        assertEquals(Component.empty(), Text.deserialize(null));
        assertEquals(Component.empty(), Text.deserialize(""));
    }

    @Test
    void deserializeNeverThrowsOnMalformedInput() {
        assertDoesNotThrow(() -> assertNotNull(Text.deserialize("<bad tag <<< &")));
        assertDoesNotThrow(() -> assertNotNull(Text.deserialize("<#zzzzzz>")));
    }

    // ---- name(): defaults to white + disables italic ----

    @Test
    void nameDefaultsToWhiteAndDisablesItalic() {
        Component name = Text.name("Hello");
        assertEquals("Hello", PLAIN.serialize(name));
        assertEquals(NamedTextColor.WHITE, effectiveColor(name));
        assertEquals(TextDecoration.State.FALSE, effectiveItalic(name));
    }

    @Test
    void namePreservesExplicitColorButStillDisablesItalic() {
        Component name = Text.name("&eYellow");
        assertEquals(NamedTextColor.YELLOW, effectiveColor(name));
        assertEquals(TextDecoration.State.FALSE, effectiveItalic(name));
    }

    @Test
    void namePreservesExplicitItalic() {
        Component name = Text.name("<italic>Fancy");
        assertEquals(TextDecoration.State.TRUE, effectiveItalic(name));
    }

    // ---- lore(): defaults to gray (never dark purple) + disables italic ----

    @Test
    void loreDefaultsToGrayAndDisablesItalic() {
        Component lore = Text.lore("Plain line");
        assertEquals("Plain line", PLAIN.serialize(lore));
        assertEquals(NamedTextColor.GRAY, effectiveColor(lore));
        assertEquals(TextDecoration.State.FALSE, effectiveItalic(lore));
    }

    @Test
    void lorePreservesExplicitColor() {
        Component lore = Text.lore("&cDanger");
        assertEquals(NamedTextColor.RED, effectiveColor(lore));
        assertEquals(TextDecoration.State.FALSE, effectiveItalic(lore));
    }

    @Test
    void loreSupportsHexColor() {
        Component lore = Text.lore("&#ff8800Warm");
        assertEquals(TextColor.fromHexString("#ff8800"), effectiveColor(lore));
        assertEquals("Warm", PLAIN.serialize(lore));
    }

    @Test
    void plainStripsAllFormatting() {
        assertEquals("Green White", Text.plain("&aGreen &fWhite"));
        assertEquals("Bold", Text.plain("<bold>Bold</bold>"));
        assertEquals("", Text.plain(null));
    }
}
