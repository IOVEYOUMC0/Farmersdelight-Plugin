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
}
