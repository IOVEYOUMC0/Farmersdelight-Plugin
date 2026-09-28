package com.huidu.farmersdelight.util;

import net.momirealms.craftengine.core.plugin.config.ConfigConstants;
import net.momirealms.craftengine.core.plugin.config.KnownResourceException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BehaviorArgParserTest {

    @Test
    void parsesPrimitiveValuesFromStringsAndNumbers() {
        Map<String, Object> args = Map.of(
                "enabled", "true",
                "count", "12",
                "scale", 0.75D
        );

        assertTrue(BehaviorArgParser.getBoolean(args, "enabled", false));
        assertEquals(12, BehaviorArgParser.getInt(args, "count", 0));
        assertEquals(0.75F, BehaviorArgParser.getFloat(args, "scale", 0.0F));
    }

    @Test
    void defaultsMissingValuesAndRejectsInvalidValuesWithTheirConfigPaths() {
        Map<String, Object> args = Map.of(
                "count", "bad",
                "scale", "bad",
                "blank", " "
        );

        assertEquals(7, BehaviorArgParser.getInt(args, "missing-count", 7));
        assertEquals(1.5F, BehaviorArgParser.getFloat(args, "missing-scale", 1.5F));
        var count = assertThrows(KnownResourceException.class, () -> BehaviorArgParser.getInt(args, "count", 7));
        assertEquals("count", count.node());
        assertEquals(ConfigConstants.PARSE_INT_FAILED, count.translationKey());
        var scale = assertThrows(KnownResourceException.class, () -> BehaviorArgParser.getFloat(args, "scale", 1.5F));
        assertEquals("scale", scale.node());
        assertEquals(ConfigConstants.PARSE_FLOAT_FAILED, scale.translationKey());
        assertFalse(BehaviorArgParser.hasArgument(args, "blank"));
        assertEquals("fallback", BehaviorArgParser.getArgumentString(args, "missing-text", "fallback"));
        var blank = assertThrows(KnownResourceException.class,
                () -> BehaviorArgParser.getArgumentString(args, "blank", "fallback"));
        assertEquals("blank", blank.node());
        assertEquals(ConfigConstants.PARSE_NONEMPTY_STRING_FAILED, blank.translationKey());
    }

    @Test
    void resolvesUnderscoreWhenLookupUsesHyphen() {
        Map<String, Object> args = Map.of(
                "grow_speed", "0.5",
                "is_bone_meal_target", "true",
                "light_requirement", "12"
        );

        assertEquals(0.5F, BehaviorArgParser.getFloat(args, "grow-speed", 0.0F));
        assertTrue(BehaviorArgParser.getBoolean(args, "is-bone-meal-target", false));
        assertEquals(12, BehaviorArgParser.getInt(args, "light-requirement", 0));
        assertTrue(BehaviorArgParser.hasArgument(args, "grow-speed"));
        assertTrue(BehaviorArgParser.isPresent(args, "light-requirement"));
    }

    @Test
    void resolvesHyphenWhenLookupUsesUnderscore() {
        Map<String, Object> args = Map.of(
                "grow-speed", "0.5",
                "half-lower-value", "lower"
        );

        assertEquals(0.5F, BehaviorArgParser.getFloat(args, "grow_speed", 0.0F));
        assertEquals("lower", BehaviorArgParser.getString(args, "half_lower_value", "x"));
        assertTrue(BehaviorArgParser.hasArgument(args, "grow_speed"));
    }

    @Test
    void givenSpellingWinsOverAlternateOnConflict() {
        Map<String, Object> args = Map.of(
                "grow-speed", "0.25",
                "grow_speed", "0.99"
        );

        assertEquals(0.25F, BehaviorArgParser.getFloat(args, "grow-speed", 0.0F));
        assertEquals(0.99F, BehaviorArgParser.getFloat(args, "grow_speed", 0.0F));
    }

    // Grouped options read nested ("burn: {enabled, damage}") and keep reading the legacy flat keys
    // ("burn-enabled" / "burn_damage"), so a pack written before the nested style keeps working unchanged.
    @Test
    void readsGroupedOptionsNestedOrFromTheLegacyFlatKeys() {
        Map<String, Object> nested = Map.of("burn", Map.of("enabled", false, "damage", 2.5D));
        assertFalse(BehaviorArgParser.getBoolean(nested, "burn.enabled", true));
        assertEquals(2.5F, BehaviorArgParser.getFloat(nested, "burn.damage", 1.0F));
        assertTrue(BehaviorArgParser.isPresent(nested, "burn.enabled"));
        assertEquals(2, BehaviorArgParser.getNestedSection(nested, "burn").size());

        Map<String, Object> flat = Map.of("burn-enabled", true, "burn_damage", "1.5");
        assertTrue(BehaviorArgParser.getBoolean(flat, "burn.enabled", false));
        assertEquals(1.5F, BehaviorArgParser.getFloat(flat, "burn.damage", 1.0F));
        assertTrue(BehaviorArgParser.hasArgument(flat, "burn.enabled"));

        // Deeper paths and a missing nested value both fall back to the flat spelling of the whole path.
        Map<String, Object> deeper = Map.of("handle-toggle-sound-pitch", "1.25");
        assertEquals(1.25F, BehaviorArgParser.getFloat(deeper, "handle-toggle-sound.pitch", 1.0F));
        assertEquals(0.5F, BehaviorArgParser.getFloat(deeper, "handle-toggle-sound.volume", 0.5F));

        // The nested form wins when both are present, and an invalid nested value still fails loudly.
        Map<String, Object> both = Map.of("burn", Map.of("damage", 3.0D), "burn-damage", 1.0D);
        assertEquals(3.0F, BehaviorArgParser.getFloat(both, "burn.damage", 1.0F));
        Map<String, Object> invalid = Map.of("burn", Map.of("enabled", "perhaps"));
        assertThrows(KnownResourceException.class,
                () -> BehaviorArgParser.getBoolean(invalid, "burn.enabled", true));
    }

    // A few grouped options had a flat key that does not follow the path spelling ("is-bone-meal-target" for
    // "bone-meal.is-target", "soup-boil-sound" for "boil.soup-sound"), so the path spelling alone is not enough
    // to keep the released packs working; those readers name the old key explicitly.
    @Test
    void readsGroupedOptionsWhoseLegacyKeyIsSpelledDifferently() {
        Map<String, Object> args = Map.of(
                "is-bone-meal-target", "false",
                "soup-boil-sound", "farmersdelight:block.cooking_pot.boil_soup"
        );

        assertFalse(BehaviorArgParser.getBoolean(args, "bone-meal.is-target", "is-bone-meal-target", true));
        assertEquals("farmersdelight:block.cooking_pot.boil_soup",
                BehaviorArgParser.getStringStrict(args, "boil.soup-sound", "soup-boil-sound", null));
        assertEquals("farmersdelight:block.cooking_pot.boil_soup",
                BehaviorArgParser.getRaw(args, "boil.soup-sound", "soup-boil-sound"));
        assertTrue(BehaviorArgParser.isPresent(args, "bone-meal.is-target", "is-bone-meal-target"));

        // The grouped path still wins over the old flat key, and an absent pair keeps its default.
        Map<String, Object> both = Map.of(
                "boil", Map.of("soup-sound", "nested:soup"),
                "soup-boil-sound", "flat:soup"
        );
        assertEquals("nested:soup",
                BehaviorArgParser.getStringStrict(both, "boil.soup-sound", "soup-boil-sound", null));
        assertTrue(BehaviorArgParser.getBoolean(Map.of(), "bone-meal.is-target", "is-bone-meal-target", true));

        // The legacy value is added to the section rather than replacing it, so a sibling that the pack already
        // wrote there is still read. The readers look a dotted path up through nested maps, so a copy that carried
        // the path as one literal key would lose the legacy value instead of honouring it.
        Map<String, Object> mixed = Map.of(
                "boil", Map.of("sound", "nested:boil"),
                "soup-boil-sound", "flat:soup"
        );
        assertEquals("nested:boil", BehaviorArgParser.getStringStrict(mixed, "boil.sound", null));
        assertEquals("flat:soup",
                BehaviorArgParser.getStringStrict(mixed, "boil.soup-sound", "soup-boil-sound", null));
        Map<String, Object> nestedSupport = Map.of(
                "support", Map.of("display", true),
                "require-non-full-support", "false"
        );
        assertTrue(BehaviorArgParser.getBoolean(nestedSupport, "support.display", "display-support", false));
        assertFalse(BehaviorArgParser.getBoolean(nestedSupport, "support.require-non-full",
                "require-non-full-support", true));
    }
}
