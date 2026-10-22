package com.huidu.farmersdelight.util.compat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KaleidoscopeCompatTest {

    @Test
    void parsesTheRecordedIdsInOrder() {
        assertEquals(List.of("minecraft:beef", "farmersdelight:tomato"),
                KaleidoscopeCompat.parseIngredients("minecraft:beef,farmersdelight:tomato"));
    }

    @Test
    void skipsSegmentsThatAreNotItemIds() {
        assertEquals(List.of("minecraft:beef", "minecraft:porkchop"),
                KaleidoscopeCompat.parseIngredients("minecraft:beef,not a key,minecraft:porkchop"));
    }

    @Test
    void refusesMoreSegmentsThanTheRecipeItemCanRecord() {
        assertTrue(KaleidoscopeCompat.parseIngredients(
                "a:a,a:b,a:c,a:d,a:e,a:f,a:g,a:h,a:i,a:j").isEmpty());
    }

    @Test
    void refusesEmptyAndOverlongRecordings() {
        assertTrue(KaleidoscopeCompat.parseIngredients(null).isEmpty());
        assertTrue(KaleidoscopeCompat.parseIngredients("  ").isEmpty());
        assertTrue(KaleidoscopeCompat.parseIngredients("x".repeat(2048)).isEmpty());
    }
}
