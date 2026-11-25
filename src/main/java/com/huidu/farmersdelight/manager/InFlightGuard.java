package com.huidu.farmersdelight.manager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
 * Every successful claim carries a token. complete(Object, long) only releases the marker when that
 * token is still the current one, so a task belonging to an expired claim cannot clear the claim that replaced it.
 * Lifecycle paths (block removed, world unloaded) use release(Object), which drops the marker regardless.
 *
 * @param <K> the tracked-block key
 */
final class InFlightGuard<K> {

    /** Ten seconds at 20 TPS, i.e. fifty passes of the four-tick station loops (tick clock). */
    static final long DEFAULT_TIMEOUT_TICKS = 200L;

    private record Claim(long submittedAt, long token) {
    }

    private final Map<K, Claim> submitted = new ConcurrentHashMap<>();
    private final AtomicLong claimSequence = new AtomicLong();
    private final long timeout;

    InFlightGuard() {
        this(DEFAULT_TIMEOUT_TICKS);
    }

    InFlightGuard(long timeout) {
        this.timeout = Math.max(1L, timeout);
    }

    /**
     * Claims the key for one submission. Returns the claim token (always non-zero), or 0 when another claim is
     * still outstanding. A marker past the timeout is replaced by this claim.
     */
    long tryAdd(K key, long now) {
        if (key == null) {
            return 0L;
        }
        long token = claimSequence.incrementAndGet();
        AtomicLong claimed = new AtomicLong();
        submitted.compute(key, (ignored, previous) -> {
            if (previous == null || now - previous.submittedAt() >= timeout) {
                claimed.set(token);
                return new Claim(now, token);
            }
            return previous;
        });
        return claimed.get();
    }

    /** Releases the marker only when the caller still owns it; a late task of an old claim is a no-op. */
    void complete(K key, long token) {
        if (key == null || token == 0L) {
            return;
        }
        submitted.computeIfPresent(key, (ignored, current) -> current.token() == token ? null : current);
    }

    /** Drops the marker regardless of token; used when the block itself stops being tracked. */
    void release(K key) {
        if (key != null) {
            submitted.remove(key);
        }
    }

    /** Whether the key is still marked after the timeout, i.e. its task was accepted but never ran. */
    boolean isStale(K key, long now) {
        if (key == null) {
            return false;
        }
        Claim previous = submitted.get(key);
        return previous != null && now - previous.submittedAt() >= timeout;
    }

    void clear() {
        submitted.clear();
    }
}
