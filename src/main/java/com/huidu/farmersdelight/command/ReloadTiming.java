package com.huidu.farmersdelight.command;

import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The elapsed time of the last targeted reload, plus the two formatting rules the report needs.
 */
public final class ReloadTiming {

    private static final AtomicLong TARGETED_NANOS = new AtomicLong();

    private ReloadTiming() {
    }

    /** Milliseconds for a report: a pass that did any work at all shows at least 1ms, never 0. */
    public static long millis(long nanos) {
        if (nanos <= 0L) {
            return 0L;
        }
        return Math.max(1L, nanos / 1_000_000L);
    }

    /** The total to report: the phase sum when a full reload filled it, else the targeted pass's own time. */
    public static long totalMillis(long phasedNanos) {
        return millis(phasedNanos > 0L ? phasedNanos : TARGETED_NANOS.get());
    }

    /** Records how long the targeted pass just took (nanoseconds). */
    public static void recordTargeted(long nanos) {
        TARGETED_NANOS.set(Math.max(0L, nanos));
    }

    /** Forgets the last targeted pass; used when a full reload resets its own figures. */
    public static void resetTargeted() {
        TARGETED_NANOS.set(0L);
    }

    /** The fixed order and names of the phase figures, in the order they are reported. */
    private static final String[] PHASE_NAMES = {"config", "tools", "managers", "listeners", "addons"};

    /**
     * Renders the phase list for one pass, or an empty string when there are no trustworthy phases.
     */
    public static String composeSegments(long totalMillis, long[] phaseNanos) {
        if (phaseNanos == null || phaseNanos.length == 0) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        long sumNanos = 0L;
        for (int index = 0; index < phaseNanos.length && index < PHASE_NAMES.length; index++) {
            long nanos = phaseNanos[index];
            if (nanos <= 0L) {
                continue;
            }
            sumNanos += nanos;
            if (text.length() > 0) {
                text.append(" / ");
            }
            text.append(PHASE_NAMES[index]).append(' ').append(Math.max(1L, nanos / 1_000_000L)).append("ms");
        }
        if (text.length() == 0) {
            return "";
        }
        // Milliseconds are floored per phase, so allow the rendering's own rounding slack, and drop the whole
        // list when it cannot fit inside this pass: those figures belong to another pass.
        if (sumNanos / 1_000_000L > totalMillis + PHASE_NAMES.length) {
            return "";
        }
        return text.toString();
    }

    /** The phase list with its separator, or nothing at all when there are no phases to show. */
    public static String split(@Nullable String summary, @Nullable String separator) {
        if (summary == null || summary.isEmpty()) {
            return "";
        }
        return (separator == null ? "" : separator) + summary;
    }
}
