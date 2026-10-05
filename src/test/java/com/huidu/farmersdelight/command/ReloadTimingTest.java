package com.huidu.farmersdelight.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The reload report must never claim 0ms for work that happened, and never dangle its separator. */
class ReloadTimingTest {

    /** The report line rules that the field logs broke: no empty segment, fixed order, never a stale one. */
    @Test
    void thePhaseListIsFixedAndNeverEmpty() {
        long ms = 1_000_000L;
        assertEquals("", ReloadTiming.composeSegments(100L, new long[]{0L, 0L, 0L, 0L, 0L}),
                "no phases ran: nothing may be printed after the total");
        assertEquals("config 12ms / tools 3ms",
                ReloadTiming.composeSegments(100L, new long[]{12 * ms, 3 * ms, 0L, 0L, 0L}),
                "a phase that did not run is left out, and the order never varies");
        assertEquals("config 12ms / addons 5ms",
                ReloadTiming.composeSegments(100L, new long[]{12 * ms, 0L, 0L, 0L, 5 * ms}),
                "gaps do not shift the remaining names");
    }

    @Test
    void segmentsThatCannotBelongToTheTotalAreDropped() {
        long ms = 1_000_000L;
        assertEquals("", ReloadTiming.composeSegments(6L, new long[]{0L, 0L, 0L, 0L, 116 * ms}),
                "a leftover addon figure next to a 6ms total is not this pass's: drop it (the field log)");
        assertEquals("addons 116ms", ReloadTiming.composeSegments(300L, new long[]{0L, 0L, 0L, 0L, 116 * ms}),
                "but it is reported when it fits");
        assertEquals("", ReloadTiming.composeSegments(100L, null), "no figures, no segments");
    }

    /** One renderer for both channels: re-inlining the label/unit/separator logic has to fail here. */
    @Test
    void consoleSummaryAndPlayerMessageShareOneRenderer() throws Exception {
        String plugin = read("FarmersDelightPlugin.java");
        assertTrue(plugin.contains("ReloadTiming.composeSegments(reloadTotalMillis(), reloadPhaseNanos())"),
                "the console summary has to come from the same renderer as the player report");
        assertFalse(plugin.contains("appendTiming("),
                "and must not keep its own label/unit/separator loop");
    }

    private static String read(String name) throws Exception {
        java.nio.file.Path cursor = java.nio.file.Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 4 && cursor != null; depth++) {
            for (String prefix : new String[]{"FarmersDelight/src/main/java/com/huidu/farmersdelight/",
                    "src/main/java/com/huidu/farmersdelight/"}) {
                java.nio.file.Path candidate = cursor.resolve(prefix + name);
                if (java.nio.file.Files.isRegularFile(candidate)) {
                    return java.nio.file.Files.readString(candidate);
                }
            }
            cursor = cursor.getParent();
        }
        throw new AssertionError("source not reachable: " + name);
    }

    @Test
    void aPassThatDidAnyWorkIsNeverReportedAsZero() {
        assertEquals(0L, ReloadTiming.millis(0L), "nothing ran, nothing to report");
        assertEquals(1L, ReloadTiming.millis(1L), "a sub-millisecond pass still shows 1ms");
        assertEquals(1L, ReloadTiming.millis(999_999L));
        assertEquals(3L, ReloadTiming.millis(3_400_000L), "real milliseconds are kept");
    }

    @Test
    void aTargetedPassFallsBackToItsOwnMeasurement() {
        ReloadTiming.resetTargeted();
        ReloadTiming.recordTargeted(12_000_000L);
        assertEquals(12L, ReloadTiming.totalMillis(0L), "targeted pass: its own time is the total");
        assertEquals(5L, ReloadTiming.totalMillis(5_000_000L), "a full reload keeps its phase sum");
        ReloadTiming.resetTargeted();
        assertEquals(0L, ReloadTiming.totalMillis(0L), "nothing measured and nothing phased");
    }

    @Test
    void theSeparatorOnlyAppearsWithAPhaseList() {
        assertEquals("", ReloadTiming.split("", ": "), "no phases: no dangling colon");
        assertEquals("", ReloadTiming.split(null, ": "));
        assertEquals(": config 3ms", ReloadTiming.split("config 3ms", ": "));
    }
}
