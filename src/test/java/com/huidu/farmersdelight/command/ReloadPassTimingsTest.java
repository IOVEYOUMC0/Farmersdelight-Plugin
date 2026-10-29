package com.huidu.farmersdelight.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReloadPassTimingsTest {

    @Test
    void aNewPassDropsThePreviousPassFigures() {
        ReloadPassTimings timings = new ReloadPassTimings();
        timings.recordPhases(1_000_000L, 2_000_000L, 3_000_000L, 4_000_000L);
        timings.recordAddons(5_000_000L);
        timings.begin(false);
        assertArrayEqualsQuiet(new long[]{0L, 0L, 0L, 0L, 0L}, timings.phaseNanos());
    }

    @Test
    void theTotalCountsThePassPhasesAndLeavesTheAddonPassOut() {
        ReloadPassTimings timings = new ReloadPassTimings();
        timings.begin(false);
        timings.recordPhases(2_000_000L, 3_000_000L, 4_000_000L, 5_000_000L);
        timings.recordAddons(90_000_000L);
        long[] phases = timings.phaseNanos();
        assertEquals(90_000_000L, phases[4], "the addon figure is still reported on its own");
        assertTrue(timings.totalMillis() < 90L, "but it is not part of the total");
    }

    @Test
    void onlyACommandStartedPassIsMarkedAsCommandDriven() {
        ReloadPassTimings timings = new ReloadPassTimings();
        timings.begin(true);
        assertTrue(timings.drivenByCommand());
        timings.end();
        assertFalse(timings.drivenByCommand(), "an ended pass cannot keep suppressing its console line");
        timings.begin(false);
        assertFalse(timings.drivenByCommand());
    }

    @Test
    void addonTimingsArrivingLaterStillLandOnTheCurrentPass() {
        ReloadPassTimings timings = new ReloadPassTimings();
        timings.begin(false);
        timings.recordPhases(1_000_000L, 1_000_000L, 1_000_000L, 1_000_000L);
        timings.recordAddons(0L);
        timings.recordAddons(7_000_000L);
        assertEquals(7_000_000L, timings.phaseNanos()[4]);
        assertNotEquals(0L, timings.phaseNanos()[3]);
    }

    private static void assertArrayEqualsQuiet(long[] expected, long[] actual) {
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], "phase " + i);
        }
    }
}
