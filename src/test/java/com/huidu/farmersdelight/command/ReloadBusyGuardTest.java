package com.huidu.farmersdelight.command;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the reload busy-guard: one reload at a time, plus a short cooldown so a burst of commands cannot stack
 * the work of FD and its addons into consecutive ticks.
 *
 *
 * Refusals are collected as the remaining-seconds value the guard reports (null while a reload is
 * still running), which is the part the player-visible message is built from.
 */
class ReloadBusyGuardTest {

    /** A clock the test advances by hand, so no timing assumptions are baked into the assertions. */
    private static final class Clock implements LongSupplier {
        private long now;

        @Override
        public long getAsLong() {
            return now;
        }

        void advance(long millis) {
            now += millis;
        }
    }

    private static ReloadBusyGuard guard(Clock clock, long cooldownMillis, List<Integer> refusals) {
        return new ReloadBusyGuard(() -> cooldownMillis, clock, refusals::add);
    }

    @Test
    void aSecondReloadIsRejectedWhileTheFirstIsStillRunning() {
        Clock clock = new Clock();
        List<Integer> refusals = new ArrayList<>();
        ReloadBusyGuard guard = guard(clock, 1000, refusals);

        assertTrue(guard.begin(), "the first reload must be accepted");
        // No time passes: the command layer would call this again on the very same tick.
        assertFalse(guard.begin(), "a concurrent reload must be rejected");
        assertEquals(1, refusals.size(), "the rejection must be reported exactly once");
        // Still running, so the cooldown has not started and there is no countdown to show.
        assertNull(refusals.getFirst(), "a running reload reports no wait time");
    }

    @Test
    void aReloadRightAfterTheLastOneFinishedIsStillRejected() {
        Clock clock = new Clock();
        List<Integer> refusals = new ArrayList<>();
        ReloadBusyGuard guard = guard(clock, 1000, refusals);

        assertTrue(guard.begin());
        guard.finish();
        // The addon cascade for the reload just accepted runs on the next tick, so an immediate repeat would
        // stack onto it. The cooldown exists to cover exactly that window.
        assertFalse(guard.begin(), "a repeat inside the cooldown must be rejected");
        assertEquals(1, refusals.size());
    }

    @Test
    void aReloadAfterTheCooldownIsAccepted() {
        Clock clock = new Clock();
        List<Integer> refusals = new ArrayList<>();
        ReloadBusyGuard guard = guard(clock, 1000, refusals);

        assertTrue(guard.begin());
        guard.finish();
        clock.advance(999);
        assertFalse(guard.begin(), "one millisecond short of the cooldown must still be rejected");

        clock.advance(1);
        assertTrue(guard.begin(), "at the cooldown boundary the guard must open again");
        assertEquals(1, refusals.size(), "only the rejected attempt is reported");
    }

    @Test
    void finishingAlwaysOpensTheGuardAgain() {
        Clock clock = new Clock();
        List<Integer> refusals = new ArrayList<>();
        ReloadBusyGuard guard = guard(clock, 5000, refusals);

        assertTrue(guard.begin());
        // A reload that fails part-way still has to release the guard, or every later reload would be refused.
        guard.finish();
        clock.advance(5000);
        assertTrue(guard.begin(), "a released guard must accept a reload once the cooldown passed");
    }

    @Test
    void aZeroCooldownStillSerialisesReloads() {
        Clock clock = new Clock();
        List<Integer> refusals = new ArrayList<>();
        ReloadBusyGuard guard = guard(clock, 0, refusals);

        assertTrue(guard.begin());
        assertFalse(guard.begin(), "even without a cooldown, an in-flight reload is exclusive");
        guard.finish();
        assertTrue(guard.begin(), "with no cooldown the guard opens immediately after finishing");
        assertEquals(1, refusals.size());
    }

    @Test
    void theRejectionCarriesTheRemainingWait() {
        Clock clock = new Clock();
        List<Integer> refusals = new ArrayList<>();
        ReloadBusyGuard guard = guard(clock, 1000, refusals);

        assertTrue(guard.begin());
        guard.finish();
        clock.advance(400);
        assertFalse(guard.begin());
        assertEquals(List.of(1), refusals, "600ms left must be reported as one second, not zero");
    }

    /** The rounding is what the player actually reads, so it is asserted directly. */
    @Test
    void remainingSecondsRoundsUpAndReportsNothingWhenOpen() {
        // Open: no cooldown configured, or the window has already elapsed.
        assertNull(ReloadBusyGuard.remainingSeconds(0L, 0L, false));
        assertNull(ReloadBusyGuard.remainingSeconds(1000L, 1000L, false));
        assertNull(ReloadBusyGuard.remainingSeconds(1000L, 5000L, false));
        // In flight: the cooldown has not started, so there is no countdown to show.
        assertNull(ReloadBusyGuard.remainingSeconds(1000L, 0L, true));
        // A sub-second remainder rounds up rather than showing "0 seconds".
        assertEquals(Integer.valueOf(1), ReloadBusyGuard.remainingSeconds(1000L, 400L, false));
        assertEquals(Integer.valueOf(1), ReloadBusyGuard.remainingSeconds(1000L, 999L, false));
        assertEquals(Integer.valueOf(2), ReloadBusyGuard.remainingSeconds(2000L, 1L, false));
    }
}
