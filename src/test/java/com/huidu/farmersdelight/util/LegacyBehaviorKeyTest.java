package com.huidu.farmersdelight.util;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the grouped options whose old flat key does not follow the <section>-<key> spelling, so the reader
 * has to name the old key explicitly. Each case passes a legacy value that differs from the default and asserts the
 * typed reader returns it, because a reader that only looked at the nested path would silently keep its default
 * (which is how a released pack could end up ignoring display-support: false).
 */
class LegacyBehaviorKeyTest {

    @Test
    void everyBooleanLegacyKeyOverridesItsDefault() {
        Map<String, String> pairs = Map.of(
                "support.display", "display-support",
                "support.require-non-full", "require-non-full-support",
                "bone-meal.is-target", "is-bone-meal-target",
                "place-on.overrides-default", "place-on-overrides-default"
        );

        for (Map.Entry<String, String> entry : pairs.entrySet()) {
            String path = entry.getKey();
            String legacy = entry.getValue();
            assertFalse(BehaviorArgParser.getBoolean(Map.of(legacy, "false"), path, legacy, true),
                    path + " must read the legacy value " + legacy);
            assertTrue(BehaviorArgParser.getBoolean(Map.of(legacy, "true"), path, legacy, false),
                    path + " must read the legacy value " + legacy);
        }
    }

    @Test
    void everyStringLegacyKeyOverridesItsDefault() {
        Map<String, String> pairs = Map.of(
                "mushroom-colony.brown", "brown-mushroom-colony",
                "mushroom-colony.red", "red-mushroom-colony",
                "ignite.fire-charge-sound", "fire-charge-sound",
                "extinguish.water-sound", "water-extinguish-sound",
                "boil.soup-sound", "soup-boil-sound",
                "pair.property", "paired-property"
        );

        for (Map.Entry<String, String> entry : pairs.entrySet()) {
            String path = entry.getKey();
            String legacy = entry.getValue();
            assertEquals("legacy:value",
                    BehaviorArgParser.getStringStrict(Map.of(legacy, "legacy:value"), path, legacy, "fallback"),
                    path + " must read the legacy value " + legacy);
        }
    }

    // The flat spellings that only add a dash ("handle-toggle-sound-volume" for "handle-toggle-sound.volume") are
    // resolved by the shared lookup, but they still have to reach the typed readers, so both spellings of a grouped
    // option are asserted here as well.
    @Test
    void everyDashedLegacyKeyOverridesItsDefault() {
        assertEquals(0.25F, BehaviorArgParser.getFloat(
                Map.of("handle-toggle-sound-volume", "0.25"), "handle-toggle-sound.volume", 0.7F));
        assertEquals(1.75F, BehaviorArgParser.getFloat(
                Map.of("sound-pitch-max", "1.75"), "sound.pitch-max", 1.1F));
        assertEquals(4, BehaviorArgParser.getInt(
                Map.of("max-age-lower", "4"), "max-age.lower", 1));
        assertEquals("upper", BehaviorArgParser.getString(
                Map.of("half-lower-value", "upper"), "half.lower-value", "lower"));
    }
}
