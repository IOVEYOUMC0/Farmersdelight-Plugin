package com.huidu.farmersdelight.manager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Per-key cooldown for repeated warnings.
 */
final class WarningThrottle {

    private final Map<String, Long> lastWarnedAt = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    WarningThrottle(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Whether a warning for key may be emitted now, recording the time when it may.
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
