package com.huidu.farmersdelight.manager;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The defect this guards: a block is deactivated while its work pass is already in flight, then re-activated
 * before that pass runs. The block is active again by the time the task checks, so a membership test lets the
 * stale pass through next to the pass the re-activation submitted, and the block is worked twice.
 */
class ActivationGenerationsTest {

    @Test
    void aPassStaysCurrentWhileItsActivationDoes() {
        ActivationGenerations<String> generations = new ActivationGenerations<>();
        long first = generations.activate("pot@1,64,1");

        assertTrue(generations.isCurrent("pot@1,64,1", first));
        assertEquals(first, generations.current("pot@1,64,1"));
    }

    @Test
    void aReactivationSupersedesThePassThatWasAlreadyInFlight() {
        ActivationGenerations<String> generations = new ActivationGenerations<>();
        String key = "pot@1,64,1";
        long inFlight = generations.activate(key);

        generations.deactivate(key);
        assertFalse(generations.isCurrent(key, inFlight), "a deactivated block has no current pass");

        long reactivated = generations.activate(key);
        assertTrue(generations.isCurrent(key, reactivated));
        assertFalse(generations.isCurrent(key, inFlight),
                "the stale pass would otherwise run alongside the new one and work the block twice");
    }

    @Test
    void anUnknownKeyHasNoGeneration() {
        ActivationGenerations<String> generations = new ActivationGenerations<>();
        assertNull(generations.current("never-seen"));
        assertFalse(generations.isCurrent("never-seen", 1L));
        assertEquals(0, generations.size());
    }

    @Test
    void generationsAreIndependentPerKey() {
        ActivationGenerations<String> generations = new ActivationGenerations<>();
        long first = generations.activate("pot@1,64,1");
        long second = generations.activate("pot@2,64,1");

        assertNotNull(generations.current("pot@2,64,1"));
        assertTrue(generations.isCurrent("pot@1,64,1", first));
        assertTrue(generations.isCurrent("pot@2,64,1", second));
        assertFalse(generations.isCurrent("pot@2,64,1", first));

        generations.deactivate("pot@1,64,1");
        assertEquals(1, generations.size());
        assertTrue(generations.isCurrent("pot@2,64,1", second), "one key's exit must not clear another's");
    }

    @Test
    void clearDropsEveryActivation() {
        ActivationGenerations<String> generations = new ActivationGenerations<>();
        long generation = generations.activate("pot@1,64,1");

        generations.clear();

        assertNull(generations.current("pot@1,64,1"));
        assertFalse(generations.isCurrent("pot@1,64,1", generation));
    }
}
