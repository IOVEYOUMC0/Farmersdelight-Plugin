package com.huidu.farmersdelight.effect;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure rules behind the pack-declared effect functions: amplifier capping, id canonicalisation and which
 * effects count as harmful. The mutation itself needs a live player and is covered by the source contracts.
 */
class ContentEffectFunctionTest {

    @Test
    void theAmplifierNeverExceedsTheCap() {
        assertEquals(4, ContentEffectFunction.upgradedAmplifier(3, Integer.MAX_VALUE, 4));
        assertEquals(0, ContentEffectFunction.upgradedAmplifier(3, Integer.MIN_VALUE, 4));
        assertEquals(2, ContentEffectFunction.upgradedAmplifier(1, 1, 4));
    }

    @Test
    void aNegativeCapFloorsAtZeroInsteadOfWrapping() {
        assertEquals(0, ContentEffectFunction.upgradedAmplifier(1, 1, -1));
        assertEquals(0, ContentEffectFunction.upgradedAmplifier(5, -10, 4));
    }

    @Test
    void theBuiltInBuffsAreCanonicalisedFromEveryAcceptedSpelling() {
        assertEquals("farmersdelight:nourishment", ContentEffectFunction.canonicalEffectId("nourishment"));
        assertEquals("farmersdelight:nourishment", ContentEffectFunction.canonicalEffectId("nourishment_effect"));
        assertEquals("farmersdelight:nourishment", ContentEffectFunction.canonicalEffectId("farmersdelight:nourishment_effect"));
        assertEquals("farmersdelight:comfort", ContentEffectFunction.canonicalEffectId("comfort"));
        assertEquals("farmersdelight:comfort", ContentEffectFunction.canonicalEffectId("farmersdelight:comfort_effect"));
    }

    @Test
    void anEffectWithoutANamespaceGetsMinecraft() {
        assertEquals("minecraft:poison", ContentEffectFunction.canonicalEffectId("poison"));
        assertEquals("fixture:nourishment_effect", ContentEffectFunction.canonicalEffectId("fixture:nourishment_effect"));
    }

    @Test
    void theHarmfulSetCoversTheVanillaDebuffsAndNothingElse() {
        for (String harmful : new String[]{"poison", "darkness", "blindness", "hunger", "nausea", "slowness",
                "mining_fatigue", "weakness", "wither", "levitation", "bad_omen", "infested", "oozing",
                "weaving", "wind_charged"}) {
            assertTrue(ContentEffectFunction.isHarmful(harmful), harmful + " has to count as harmful");
        }
        for (String helpful : new String[]{"regeneration", "speed", "strength", "absorption", "fire_resistance"}) {
            assertFalse(ContentEffectFunction.isHarmful(helpful), helpful + " must not count as harmful");
        }
    }

    @Test
    void theRegisteredFunctionIdsAreTheMutationActionsPlusTheLegacyAlias() {
        assertEquals(java.util.Set.of("remove_effect", "remove_random_effect", "upgrade_effect",
                "grant_advancement", "hot_cocoa"), ContentEffectFunction.factories().keySet());
    }

    @Test
    void theLegacyIdKeepsWorkingForAPackThatWasNotUpdated() throws java.io.IOException {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/huidu/farmersdelight/effect/ContentEffectFunction.java"));
        String flat = source.replaceAll("\\s+", " ");
        // An unknown function type makes CraftEngine reject the whole item definition, so the old id has to keep
        // resolving, and it has to behave exactly like the harmful-only random removal it used to hard-code.
        assertTrue(flat.contains("result.put(\"hot_cocoa\", factory(plugin, Action.REMOVE_RANDOM, true))"),
                "the legacy id must resolve to the old behaviour");
    }

    @Test
    void theFunctionOnlyMutatesOnThePlayersOwnRegion() throws java.io.IOException {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/huidu/farmersdelight/effect/ContentEffectFunction.java"));
        String flat = source.replaceAll("\\s+", " ");
        assertTrue(flat.contains("scheduler.isOwnedByCurrentRegion(player)"),
                "a foreign thread must not write the player's effects");
        assertTrue(flat.contains("scheduler.runForEntity(player"),
                "the mutation is deferred to the entity scheduler instead of running inline");
    }
}
