package com.huidu.farmersdelight.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Periodic work that shares one period must not all start on the same tick: the delays are deterministic, spread
 * across the period, and distinct. Arming every task with delay 0 fails here.
 */
class PeriodicStaggerTest {

    @Test
    void everySlotStartsOnItsOwnTickInsideTheFirstPeriod() {
        long period = 20L;
        List<Long> firstTicks = new ArrayList<>();
        for (String slot : PeriodicStagger.slots()) {
            firstTicks.add(PeriodicStagger.firstTick(slot, period));
        }
        Set<Long> distinct = new HashSet<>(firstTicks);
        assertEquals(firstTicks.size(), distinct.size(), "each slot needs its own first tick: " + firstTicks);
        for (Long tick : firstTicks) {
            assertTrue(tick >= 1L && tick <= period, "the first tick stays inside the first period: " + tick);
        }
        assertEquals(1L, firstTicks.get(0), "the first slot keeps its immediate start");
    }

    @Test
    void theSpreadIsEvenAndDeterministic() {
        long period = 20L;
        assertEquals(0L, PeriodicStagger.initialDelay("display-cull", period));
        assertEquals(4L, PeriodicStagger.initialDelay("recipe-registration", period));
        assertEquals(8L, PeriodicStagger.initialDelay("workstation-tick", period));
        assertEquals(12L, PeriodicStagger.initialDelay("carrier-restore", period));
        assertEquals(16L, PeriodicStagger.initialDelay("effect-tick", period));
        assertEquals(PeriodicStagger.initialDelay("workstation-tick", period),
                PeriodicStagger.initialDelay("workstation-tick", period), "no randomness");
    }

    @Test
    void anUnknownSlotIsRejectedInsteadOfSilentlyStartingAtZero() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> PeriodicStagger.initialDelay("not-a-slot", 20L));
    }
}
