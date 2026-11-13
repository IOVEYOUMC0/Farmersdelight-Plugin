package com.huidu.farmersdelight.api.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** A read is recorded in every spelling the lookup accepts, so the other spelling never looks unread. */
class BehaviorArgRecorderSpellingTest {

    @Test
    void aHyphenatedReadAlsoRecordsTheUnderscoreSpelling() {
        BehaviorArgRecorder.begin();
        BehaviorArguments.raw(Map.of(), "open-while-sneaking");
        Set<String> read = BehaviorArgRecorder.end();
        assertTrue(read.contains("open_while_sneaking"), "packs may write the underscore spelling: " + read);
        assertTrue(read.contains("open-while-sneaking"));
    }

    @Test
    void anUnderscoreReadAlsoRecordsTheHyphenatedSpelling() {
        BehaviorArgRecorder.begin();
        BehaviorArguments.raw(Map.of(), "grow_speed");
        assertTrue(BehaviorArgRecorder.end().contains("grow-speed"));
    }

    @Test
    void aNestedReadRecordsItsLeafAndBothSpellings() {
        BehaviorArgRecorder.begin();
        BehaviorArguments.raw(Map.of(), "burn.enabled");
        Set<String> read = BehaviorArgRecorder.end();
        assertTrue(read.contains("burn.enabled"), "the leaf has to be recorded, not only its section: " + read);
        assertTrue(read.contains("burn-enabled"));
        assertTrue(read.contains("burn_enabled"));
    }

    @Test
    void aLegacyFlatKeyCountsAsReadForItsGroupedOption() {
        BehaviorArgRecorder.begin();
        BehaviorArguments.rawWithLegacy(Map.of("bone_meal_age_bonus", 1), "bone-meal.age-bonus",
                "bone_meal_age_bonus");
        List<String> read = List.copyOf(BehaviorArgRecorder.end());
        assertTrue(read.contains("bone_meal_age_bonus"));
        assertTrue(read.contains("bone-meal-age-bonus") || read.contains("bone_meal_age_bonus"));
    }
}
