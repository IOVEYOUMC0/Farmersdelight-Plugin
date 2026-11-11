package com.huidu.farmersdelight.api.util;

import org.jetbrains.annotations.ApiStatus;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Per-feature cooldown for console warnings about repeated failures.
 *
 * <p>A feature that fails for every block or on every tick keeps its fallback and reports once per cooldown, so
 * the log stays readable. The clock is injectable for tests.
 */
@ApiStatus.AvailableSince("6")
public final class WarningThrottle {

    /** Ten minutes: long enough that a broken configuration is reported once per session in practice. */
    public static final long DEFAULT_COOLDOWN_MILLIS = 600_000L;

    private final long cooldownMillis;
    private final LongSupplier clock;
    private final Map<String, Long> lastReported = new HashMap<>();

    public WarningThrottle() {
        this(DEFAULT_COOLDOWN_MILLIS, System::currentTimeMillis);
    }

    public WarningThrottle(long cooldownMillis, LongSupplier clock) {
        this.cooldownMillis = Math.max(0L, cooldownMillis);
        this.clock = clock;
    }

    /** True when a warning for this feature/world pair may be reported now; a true result starts its cooldown. */
    public synchronized boolean allow(String feature, String worldId) {
        long now = clock.getAsLong();
        String key = feature + "\u0000" + worldId;
        Long last = lastReported.get(key);
        if (last != null) {
            long elapsed = now - last;
            // A clock jump backwards must not suppress warnings until the old timestamp is reached again.
            if (elapsed >= 0 && elapsed < cooldownMillis) {
                return false;
            }
        }
        lastReported.values().removeIf(reportedAt -> now - reportedAt >= cooldownMillis);
        lastReported.put(key, now);
        return true;
    }

    /** Pairs currently inside their cooldown; for tests and diagnostics. */
    public synchronized int trackedKeys() {
        lastReported.values().removeIf(reportedAt -> clock.getAsLong() - reportedAt >= cooldownMillis);
        return lastReported.size();
    }
}
