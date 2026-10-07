package com.huidu.farmersdelight.util;

import java.util.List;

/**
 * First-tick delays that spread the periodic work of several features over the tick they share.
 *
 *
 * Everything that arms a repeating task with delay 0 starts on the same tick, so a server running the display
 * the cleanup pass and the display sync on one period pays for all of them in the same tick and nothing in the others. The delay is deterministic and derived from a fixed slot order:
 * initialDelay = index * interval / slotCount, never random, so a test can assert exactly which tick each task
 * first runs on.
 *
 * Only the first delay changes: the period, and everything a task decides, stays exactly as it was.
 */
public final class PeriodicStagger {

    /** Fixed slot order. Never insert or reorder an existing name: a slot's delay depends on its index and on the
     * slot count, so adding one past the end still moves every delay that follows. */
    private static final List<String> SLOTS = List.of(
            "tick-cleanup",
            "display-sync");

    private PeriodicStagger() {
    }

    /** The first-tick delay for one slot, spread over the given period: 0, period/N, 2*period/N, ... */
    public static long initialDelay(String slot, long period) {
        int index = SLOTS.indexOf(slot);
        if (index < 0) {
            throw new IllegalArgumentException("unknown periodic slot: " + slot);
        }
        long span = Math.max(1L, period);
        return (span * index) / SLOTS.size();
    }

    /** The first tick (1-based, as a scheduler sees it) each slot first runs on for the given period. */
    public static long firstTick(String slot, long period) {
        return initialDelay(slot, period) + 1L;
    }

    /** The slots, in the fixed order their delays are derived from. */
    public static List<String> slots() {
        return SLOTS;
    }
}
