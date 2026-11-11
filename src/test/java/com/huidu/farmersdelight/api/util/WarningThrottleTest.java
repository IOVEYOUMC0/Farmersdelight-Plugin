package com.huidu.farmersdelight.api.util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WarningThrottleTest {

    private final AtomicLong now = new AtomicLong(1_000L);
    private final WarningThrottle throttle = new WarningThrottle(100L, now::get);

    @Test
    void oneReportPerFeatureAndWorldInsideTheCooldown() {
        assertTrue(throttle.allow("save", "world"));
        assertFalse(throttle.allow("save", "world"));
        assertTrue(throttle.allow("save", "other-world"), "another world is a separate report");
        assertTrue(throttle.allow("load", "world"), "another feature is a separate report");
    }

    @Test
    void theCooldownExpires() {
        assertTrue(throttle.allow("save", "world"));
        now.addAndGet(99L);
        assertFalse(throttle.allow("save", "world"));
        now.addAndGet(1L);
        assertTrue(throttle.allow("save", "world"));
    }

    @Test
    void expiredEntriesAreDropped() {
        assertTrue(throttle.allow("save", "world"));
        assertTrue(throttle.allow("save", "other-world"));
        assertEquals(2, throttle.trackedKeys());
        now.addAndGet(100L);
        assertTrue(throttle.allow("load", "world"));
        assertEquals(1, throttle.trackedKeys(), "only the pair reported now is still inside its cooldown");
    }

    @Test
    void trackedKeysDropsExpiredPairsWithoutAnAllowCall() {
        assertTrue(throttle.allow("save", "world"));
        now.addAndGet(100L);
        assertEquals(0, throttle.trackedKeys());
    }

    @Test
    void aClockJumpBackwardsDoesNotSuppressForever() {
        assertTrue(throttle.allow("save", "world"));
        now.addAndGet(-5_000L);
        assertTrue(throttle.allow("save", "world"), "a backwards clock must not keep the pair suppressed");
    }

    @Test
    void featureAndWorldCannotCollideThroughTheirSeparator() {
        assertTrue(throttle.allow("save:world", "x"));
        assertTrue(throttle.allow("save", "world:x"));
    }

    @Test
    void zeroCooldownReportsEveryTime() {
        WarningThrottle always = new WarningThrottle(0L, now::get);
        assertTrue(always.allow("save", "world"));
        assertTrue(always.allow("save", "world"));
    }
}
