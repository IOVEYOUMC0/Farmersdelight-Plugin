package com.huidu.farmersdelight.manager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks the blocks whose work pass has been handed to a region scheduler but has not reported back yet, so the
 * next pass does not submit the same block twice. A region task releases its key when it finishes; a task the
 * scheduler accepted and then never ran (its region merged or shut down first) never does, so a key that survives
 * the timeout is treated as lost and may be claimed again instead of keeping the block marked forever. Re-running
 * a station pass is safe: the loops credit elapsed game ticks from their own last-credit stamp, not once per
 * submission, so the duplicate cannot advance cooking twice. The timeout is expressed in the same monotonic unit
 * the caller passes to tryAdd: station loops driven by a tick pass use ticks, loops that run as a Folia global
 * task pass nanoTime.
 *
 * @param <K> the tracked-block key
 */
final class InFlightGuard<K> {

    /** Ten seconds at 20 TPS, i.e. fifty passes of the four-tick station loops (tick clock). */
    static final long DEFAULT_TIMEOUT_TICKS = 200L;

    private final Map<K, Long> submittedAt = new ConcurrentHashMap<>();
    private final long timeout;

    InFlightGuard() {
        this(DEFAULT_TIMEOUT_TICKS);
    }

    InFlightGuard(long timeout) {
        this.timeout = Math.max(1L, timeout);
    }

    /**
     * Claims the key for one submission. Returns true when the caller may submit: nothing is in flight, or the
     * existing marker is past the timeout and is replaced by this claim.
     */
    boolean tryAdd(K key, long now) {
        if (key == null) {
            return false;
        }
        AtomicBoolean claimed = new AtomicBoolean();
        submittedAt.compute(key, (ignored, previous) -> {
            if (previous == null || now - previous >= timeout) {
                claimed.set(true);
                return now;
            }
            return previous;
        });
        return claimed.get();
    }

    /** Releases the key once its task ran or its submission was refused. */
    void complete(K key) {
        if (key != null) {
            submittedAt.remove(key);
        }
    }

    /** Whether the key is still marked after the timeout, i.e. its task was accepted but never ran. */
    boolean isStale(K key, long now) {
        if (key == null) {
            return false;
        }
        Long previous = submittedAt.get(key);
        return previous != null && now - previous >= timeout;
    }

    void clear() {
        submittedAt.clear();
    }
}
