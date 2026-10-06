package com.huidu.farmersdelight.util;

import java.util.List;

/**
 * First-tick delays that spread the periodic work of several features over the tick they share.
 *
 *
 * Everything that arms a repeating task with delay 0 starts on the same tick, so a server with a display culler, a
 * recipe pass and the workstation tickers all on one period pays for all of them in the same tick and nothing in
 * the others. The delay is deterministic and derived from a fixed slot order (the shape CraftEngine uses for its
 * entity-culling workers: {@code initialDelay = index * interval / workers}), never random, so a test can assert
 * exactly which tick each task first runs on.
 *
 * <p>Only the first delay changes: the period, and everything a task decides, stays exactly as it was.
 */
public final class PeriodicStagger {

    /** Fixed slot order. Append new names at the end: the delay of an existing slot must not move. */
    private static final List<String> SLOTS = List.of(
            "display-cull",
            "recipe-registration",
            "workstation-tick",
            "carrier-restore",
            "effect-tick");

    private PeriodicStagger() {
    }

    /** The first-tick delay for one slot, spread over {@code period} ticks: 0, period/N, 2*period/N, ... */
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
