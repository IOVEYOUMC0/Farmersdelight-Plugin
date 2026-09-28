package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.util.BehaviorArgParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CookingPotBlockBehaviorArgumentsTest {

    // The released packs wrote the handle sound under the section name itself, with the volume and pitch beside it
    // as "handle-toggle-sound-volume"/"-pitch". The grouped rewrite moved the sound to "sound:", so the scalar
    // spelling has to keep being read or those packs would silently fall back to the default lantern sound.
    @Test
    void readsTheHandleSoundFromTheOldScalarSpelling() {
        Map<String, Object> legacy = Map.of(
                "handle-toggle-sound", "custom:block.pot.handle",
                "handle-toggle-sound-volume", 0.4D,
                "handle-toggle-sound-pitch", "1.2"
        );

        assertEquals("custom:block.pot.handle", CookingPotBlockBehavior.handleToggleSoundArgument(legacy));
        assertEquals(0.4F, BehaviorArgParser.getFloat(legacy, "handle-toggle-sound.volume", 0.7F));
        assertEquals(1.2F, BehaviorArgParser.getFloat(legacy, "handle-toggle-sound.pitch", 1.0F));
    }

    @Test
    void readsTheHandleSoundFromTheGroupedSpellingAndPrefersIt() {
        Map<String, Object> grouped = Map.of(
                "handle-toggle-sound", Map.of("sound", "grouped:sound", "volume", 0.2D)
        );

        assertEquals("grouped:sound", CookingPotBlockBehavior.handleToggleSoundArgument(grouped));
        assertEquals("grouped:sound", CookingPotBlockBehavior.handleToggleSoundArgument(
                Map.of("handle-toggle-sound", Map.of("sound", "grouped:sound"),
                        "handle-toggle-sound-sound", "flat:sound")));
    }

    @Test
    void fallsBackToTheDefaultHandleSoundWhenNothingIsConfigured() {
        assertEquals("minecraft:block.lantern.place", CookingPotBlockBehavior.handleToggleSoundArgument(Map.of()));
        assertEquals("minecraft:block.lantern.place", CookingPotBlockBehavior.handleToggleSoundArgument(
                Map.of("handle-toggle-sound", Map.of("volume", 0.2D))));
        assertEquals("minecraft:block.lantern.place", CookingPotBlockBehavior.handleToggleSoundArgument(
                Map.of("handle-toggle-sound", "  ")));
    }
}
