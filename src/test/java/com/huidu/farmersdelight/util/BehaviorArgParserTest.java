package com.huidu.farmersdelight.util;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void fallsBackOnMissingOrInvalidValues() {
        Map<String, Object> args = Map.of(
                "count", "bad",
                "scale", "bad",
                "blank", " "
        );

        assertEquals(7, BehaviorArgParser.getInt(args, "count", 7));
        assertEquals(1.5F, BehaviorArgParser.getFloat(args, "scale", 1.5F));
        assertFalse(BehaviorArgParser.hasArgument(args, "blank"));
        assertEquals("fallback", BehaviorArgParser.getArgumentString(args, "blank", "fallback"));
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
}
