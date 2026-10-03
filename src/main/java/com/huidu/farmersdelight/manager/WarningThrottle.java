package com.huidu.farmersdelight.manager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Per-key cooldown for repeated warnings.
 *
 *
 * Two callers in PerformanceMonitor had their own copy of "look up the last time this key warned,
 * compare against the cooldown, remember now if allowed". Both run from repeating tasks — a density warning
 * per chunk, a failure report per block per pass — so an unthrottled report is a log flood, and the rule is
 * the part worth having in one place and under test.
 *
 *
 * The clock is injected so the cooldown can be checked without sleeping. Keys are pruned by
 * prune(long); a key pruned while still cooling down simply warns again, which is the same
 * trade-off the previous per-caller map pruning made.
 */
final class WarningThrottle {

    private final Map<String, Long> lastWarnedAt = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    WarningThrottle(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Whether a warning for key may be emitted now, recording the time when it may.
     *
     *
     * Recording happens as part of the decision, so two concurrent callers cannot both be told yes.
     */
    boolean allow(String key, long cooldownMillis) {
        long now = clock.getAsLong();
        Long previous = lastWarnedAt.get(key);
        if (previous != null && now - previous < cooldownMillis) {
            return false;
        }
        lastWarnedAt.put(key, now);
        return true;
    }

    /** Drops entries older than the cooldown; the next warning for the same key re-adds it. */
    void prune(long cooldownMillis) {
        long cutoff = clock.getAsLong() - cooldownMillis;
        lastWarnedAt.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }

    /** Number of keys currently remembered; for tests and diagnostics. */
    int trackedKeys() {
        return lastWarnedAt.size();
    }
}
