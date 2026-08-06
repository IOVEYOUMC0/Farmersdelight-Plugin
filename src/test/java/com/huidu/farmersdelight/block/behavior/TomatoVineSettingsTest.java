package com.huidu.farmersdelight.block.behavior;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TomatoVineSettingsTest {

    private static final String BLOCK_ID = "farmersdelight:tomatoes";

    private static Map<String, Object> flatArguments() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("budding-block", "test:budding");
        arguments.put("tomatoes-block", "test:tomatoes");
        arguments.put("crop-on-rope-block", "test:on_rope");
        arguments.put("rope-block", "test:rope");
        arguments.put("budding-max-age", 5);
        arguments.put("tomatoes-max-age", 6);
        arguments.put("hanging-max-age", 7);
        arguments.put("bonemeal-bonus-min", 2);
        arguments.put("bonemeal-bonus-max", 4);
        arguments.put("bonemeal-climb-chance", 0.75F);
        return arguments;
    }

    private static Map<String, Object> nestedArguments() {
        Map<String, Object> blocks = new LinkedHashMap<>();
        blocks.put("budding", "test:budding");
        blocks.put("tomatoes", "test:tomatoes");
        blocks.put("crop-on-rope", "test:on_rope");
        blocks.put("rope", "test:rope");

        Map<String, Object> maxAge = new LinkedHashMap<>();
        maxAge.put("budding", 5);
        maxAge.put("tomatoes", 6);
        maxAge.put("hanging", 7);

        Map<String, Object> bonemeal = new LinkedHashMap<>();
        bonemeal.put("bonus-min", 2);
        bonemeal.put("bonus-max", 4);
        bonemeal.put("climb-chance", 0.75F);

        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("blocks", blocks);
        arguments.put("max-age", maxAge);
        arguments.put("bonemeal", bonemeal);
        return arguments;
    }

    private static void assertSameValues(TomatoVineSettings expected, TomatoVineSettings actual) {
        assertEquals(expected.buddingBlock(), actual.buddingBlock());
        assertEquals(expected.tomatoesBlock(), actual.tomatoesBlock());
        assertEquals(expected.cropOnRopeBlock(), actual.cropOnRopeBlock());
        assertEquals(expected.ropeBlock(), actual.ropeBlock());
        assertEquals(expected.buddingMaxAge(), actual.buddingMaxAge());
        assertEquals(expected.tomatoesMaxAge(), actual.tomatoesMaxAge());
        assertEquals(expected.hangingMaxAge(), actual.hangingMaxAge());
        assertEquals(expected.bonemealBonusMin(), actual.bonemealBonusMin());
        assertEquals(expected.bonemealBonusMax(), actual.bonemealBonusMax());
        assertEquals(expected.bonemealClimbChance(), actual.bonemealClimbChance());
        assertEquals(expected.matureAge(), actual.matureAge());
        assertEquals(expected.minLight(), actual.minLight());
        assertEquals(expected.maxStackHeight(), actual.maxStackHeight());
    }

    @Test
    void flatAndGroupedFormsProduceTheSameSettings() {
        TomatoVineSettings flat = TomatoVineSettings.parse(flatArguments(), BLOCK_ID);
        TomatoVineSettings nested = TomatoVineSettings.parse(nestedArguments(), BLOCK_ID);
        assertSameValues(flat, nested);
        assertTrue(flat.warnings().isEmpty(), "the flat spelling is supported and must not warn");
        assertTrue(nested.warnings().isEmpty(), "the grouped spelling is the documented one and must not warn");
    }

    @Test
    void emptyArgumentsFallBackToTheSameDefaultsAsAnEmptyGroup() {
        Map<String, Object> emptyGroups = new LinkedHashMap<>();
        emptyGroups.put("blocks", new LinkedHashMap<String, Object>());
        emptyGroups.put("max-age", new LinkedHashMap<String, Object>());
        emptyGroups.put("bonemeal", new LinkedHashMap<String, Object>());

        TomatoVineSettings none = TomatoVineSettings.parse(new LinkedHashMap<>(), BLOCK_ID);
        TomatoVineSettings empty = TomatoVineSettings.parse(emptyGroups, BLOCK_ID);
        assertSameValues(none, empty);
    }

    @Test
    void theGroupedValueWinsWhenBothSpellingsAreWrittenAndTheClashIsReported() {
        Map<String, Object> blocks = new LinkedHashMap<>();
        blocks.put("budding", "test:from_group");

        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("blocks", blocks);
        arguments.put("budding-block", "test:from_flat");

        TomatoVineSettings settings = TomatoVineSettings.parse(arguments, BLOCK_ID);
        assertEquals("test:from_group", settings.buddingBlock());
        assertFalse(settings.warnings().isEmpty(), "writing both spellings of one setting has to be reported");
    }

    @Test
    void anUnknownKeyInsideAGroupIsReportedAndLeavesTheDefaultInPlace() {
        Map<String, Object> blocks = new LinkedHashMap<>();
        blocks.put("buding", "test:typo");

        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("blocks", blocks);

        TomatoVineSettings settings = TomatoVineSettings.parse(arguments, BLOCK_ID);
        TomatoVineSettings defaults = TomatoVineSettings.parse(new LinkedHashMap<>(), BLOCK_ID);
        assertEquals(defaults.buddingBlock(), settings.buddingBlock());
        assertFalse(settings.warnings().isEmpty(), "a misspelled key inside a group has to be reported");
    }

    @Test
    void aGroupWrittenAsASingleValueIsReportedRatherThanApplied() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("blocks", "test:not_a_section");

        TomatoVineSettings settings = TomatoVineSettings.parse(arguments, BLOCK_ID);
        TomatoVineSettings defaults = TomatoVineSettings.parse(new LinkedHashMap<>(), BLOCK_ID);
        assertEquals(defaults.buddingBlock(), settings.buddingBlock());
        assertFalse(settings.warnings().isEmpty(), "a group written as a scalar has to be reported");
    }
}
