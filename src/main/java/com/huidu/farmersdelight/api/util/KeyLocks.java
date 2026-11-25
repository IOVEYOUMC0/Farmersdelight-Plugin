package com.huidu.farmersdelight.api.util;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * Bounded striped locks keyed by an arbitrary string, e.g. a block position key.
 *
 * Two calls with equal keys use the same monitor, so a station's state is only touched by one thread at a
 * time even when callbacks arrive from different regions. Different keys may share a monitor; the footprint stays
 * constant no matter how many keys a session visits.
 */
@ApiStatus.AvailableSince("6")
public final class KeyLocks {

    /** Stripes used by the no-argument constructor. */
    public static final int DEFAULT_STRIPES = 1024;

    /** Hard cap for a requested stripe count, so a typo cannot allocate millions of monitors. */
    public static final int MAX_STRIPES = 16_384;

    private final Object[] monitors;
    private final Object nullMonitor = new Object();

    public KeyLocks() {
        this(DEFAULT_STRIPES);
    }

    /** @param stripes requested monitor count, rounded up to the next power of two; below 1 becomes 1, above
     *                 MAX_STRIPES is capped at that value */
    public KeyLocks(int stripes) {
        int target = Math.min(stripes, MAX_STRIPES);
        int size = 1;
        while (size < target) {
            size <<= 1;
        }
        monitors = new Object[size];
        for (int i = 0; i < size; i++) {
            monitors[i] = new Object();
        }
    }

    /** The stable monitor for this key; equal keys answer the same object, null uses a shared monitor. */
    public Object lockFor(@Nullable String key) {
        if (key == null) {
            return nullMonitor;
        }
        int hash = key.hashCode();
        return monitors[(hash ^ (hash >>> 16)) & (monitors.length - 1)];
    }

    /** Runs the action while holding this key's monitor. */
    public void run(@Nullable String key, Runnable action) {
        synchronized (lockFor(key)) {
            action.run();
        }
    }

    /** Runs the action while holding this key's monitor and returns its result. */
    public <T> T call(@Nullable String key, Supplier<T> action) {
        synchronized (lockFor(key)) {
            return action.get();
        }
    }

    /** Number of distinct monitors; for diagnostics and tests. */
    public int stripeCount() {
        return monitors.length;
    }
}
