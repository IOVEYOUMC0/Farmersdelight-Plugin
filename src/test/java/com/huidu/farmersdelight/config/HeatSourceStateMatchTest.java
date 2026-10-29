package com.huidu.farmersdelight.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the string rules of the heat-source state comparison. It runs on every station heat check, so the
 * rewrite that compares while parsing (instead of building a state map) has to stay exactly equivalent to the
 * previous map lookup, including the "required property absent" case.
 */
class HeatSourceStateMatchTest {

    private static final String CAMPFIRE = "minecraft:campfire[lit=true,signal_fire=false,waterlogged=false]";

    @Test
    void anEmptyRequirementMatchesAnyState() {
        assertTrue(HeatSourceConfig.statesMatch(CAMPFIRE, Map.of()));
        assertTrue(HeatSourceConfig.statesMatch("minecraft:furnace", Map.of()));
    }

    @Test
    void oneMatchingPropertyIsEnough() {
        assertTrue(HeatSourceConfig.statesMatch(CAMPFIRE, Map.of("lit", "true")));
        assertTrue(HeatSourceConfig.statesMatch(CAMPFIRE, Map.of("signal_fire", "false")));
    }

    @Test
    void everyRequiredPropertyHasToMatch() {
        assertTrue(HeatSourceConfig.statesMatch(CAMPFIRE, Map.of("lit", "true", "waterlogged", "false")));
        assertFalse(HeatSourceConfig.statesMatch(CAMPFIRE, Map.of("lit", "true", "waterlogged", "true")));
        assertFalse(HeatSourceConfig.statesMatch(CAMPFIRE, Map.of("lit", "false")));
    }

    @Test
    void aPropertyTheStateDoesNotHaveIsAMismatch() {
        assertFalse(HeatSourceConfig.statesMatch(CAMPFIRE, Map.of("powered", "false")));
        assertFalse(HeatSourceConfig.statesMatch(CAMPFIRE, Map.of("lit", "true", "powered", "false")));
    }

    @Test
    void valuesCompareIgnoringCaseLikeTheConfigDoes() {
        assertTrue(HeatSourceConfig.statesMatch(CAMPFIRE, Map.of("lit", "TRUE")));
    }

    @Test
    void aStateWithoutPropertiesOnlyMatchesAnEmptyRequirement() {
        assertFalse(HeatSourceConfig.statesMatch("minecraft:furnace", Map.of("lit", "true")));
        assertFalse(HeatSourceConfig.statesMatch(null, Map.of("lit", "true")));
    }
}
