package com.huidu.farmersdelight.manager;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the per-key warning cooldown both callers in PerformanceMonitor rely on.
 *
 *
 * These warnings come from repeating tasks, so the rule that matters is "at most one report per key per
 * cooldown, and never zero reports". The earlier inline copies could only be reasoned about; with an injected
 * clock the boundary can be checked exactly.
 */
class WarningThrottleTest {

    private static final long COOLDOWN = 1000L;

    private final AtomicLong clock = new AtomicLong();
    private final WarningThrottle throttle = new WarningThrottle(clock::get);

    @Test
    void firstWarningForAKeyIsAllowed() {
        assertTrue(throttle.allow("world:1", COOLDOWN));
    }

    @Test
    void aSecondWarningInsideTheCooldownIsSuppressed() {
        assertTrue(throttle.allow("world:1", COOLDOWN));

        clock.set(999L);
        assertFalse(throttle.allow("world:1", COOLDOWN), "still inside the cooldown");
    }

    @Test
    void theCooldownBoundaryIsInclusiveOfElapsedTime() {
        assertTrue(throttle.allow("world:1", COOLDOWN));

        clock.set(COOLDOWN - 1);
        assertFalse(throttle.allow("world:1", COOLDOWN));
        clock.set(COOLDOWN);
        assertTrue(throttle.allow("world:1", COOLDOWN), "exactly one cooldown later must warn again");
    }

    @Test
    void keysAreThrottledIndependently() {
        assertTrue(throttle.allow("world:1", COOLDOWN));
        assertTrue(throttle.allow("world:2", COOLDOWN), "a different world must not be silenced");
        assertTrue(throttle.allow("tick-stove:1", COOLDOWN), "a different feature must not be silenced");
    }

    @Test
    void anAllowedWarningRecordsItsTimeSoTheNextCallSeesIt() {
        throttle.allow("world:1", COOLDOWN);

        clock.set(500L);
        assertFalse(throttle.allow("world:1", COOLDOWN),
                "the first call must have recorded the time it allowed");
    }

    @Test
    void zeroCooldownAlwaysAllows() {
        assertTrue(throttle.allow("world:1", 0L));
        assertTrue(throttle.allow("world:1", 0L));
    }

    @Test
    void pruneDropsKeysOlderThanTheCooldown() {
        // Pruning keeps entries at or after `now - cooldown`, so the two keys must straddle that cutoff.
        long now = 10_000L;
        long cutoff = now - COOLDOWN;
        throttle.allow("before-cutoff", COOLDOWN);      // warned at t=0, so it is older than the cutoff
        clock.set(cutoff);
        throttle.allow("at-cutoff", COOLDOWN);          // warned exactly at the cutoff, so it is kept
        assertEquals(2, throttle.trackedKeys());

        clock.set(now);
        throttle.prune(COOLDOWN);

        assertEquals(1, throttle.trackedKeys(), "only the key older than the cutoff is dropped");
        assertTrue(throttle.allow("before-cutoff", COOLDOWN), "the dropped key may warn again");
    }

    @Test
    void pruneKeepsKeysStillInsideTheCooldown() {
        clock.set(900L);
        throttle.allow("recent", COOLDOWN);
        clock.set(1000L);
        throttle.allow("also-recent", COOLDOWN);

        throttle.prune(COOLDOWN);

        assertEquals(2, throttle.trackedKeys());
        assertFalse(throttle.allow("recent", COOLDOWN), "a pruned-or-kept key still cools down");
    }

    @Test
    void aPrunedKeyBecomesAllowedAgainImmediately() {
        throttle.allow("world:1", COOLDOWN);
        clock.set(100_000L);
        throttle.prune(COOLDOWN);

        assertTrue(throttle.allow("world:1", COOLDOWN),
                "pruning is about map size, not about silencing a key forever");
    }
}
