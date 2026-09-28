package com.huidu.farmersdelight.manager;

/**
 * The cursor policy for a bounded rotating sweep.
 *
 * <p>A sweep over a tracked set can be larger than one pass is allowed to cost, so each run covers a window
 * of at most budget entries and the next run continues where this one stopped. This is the arithmetic
 * only — no world access — so the wrap-around and the "everything fits" case can be checked without a server.
 *
 * <p>Extracted from TickManager's cooking-pot cleanup, which is where the policy is used: the Paper
 * path used to sweep the whole tracked set in one pass while only the Folia path was bounded, and the two now
 * share this policy.
 *
 * @param total      entries tracked when the window was planned
 * @param start      first index the window visits
 * @param count      how many entries the window visits (never more than total)
 * @param nextCursor where the following run should start
 */
record SweepWindow(int total, int start, int count, int nextCursor) {

    /**
     * @param total  number of entries currently tracked; <= 0 means nothing to sweep
     * @param cursor where the previous run stopped, or any value for a fresh start
     * @param budget the maximum number of entries one run may visit; values below 1 are treated as 1
     */
    static SweepWindow of(int total, int cursor, int budget) {
        if (total <= 0) {
            // Nothing tracked: reset so a later run starts at the beginning rather than resuming a stale offset.
            return new SweepWindow(0, 0, 0, 0);
        }
        int limit = Math.max(1, budget);
        int start = cursor >= total || cursor < 0 ? 0 : cursor;
        // Clamp to what is left from the cursor: a window never runs past the end of the set, which is what
        // keeps every index it hands out inside the set.
        int count = Math.min(limit, total - start);
        int next = start + count;
        // Landing exactly on the end wraps to the beginning; otherwise the next run resumes here. The cursor
        // therefore always points at the first entry of the next window.
        return new SweepWindow(total, start, count, next >= total ? 0 : next);
    }

    /** The index visited for this window's processed-th entry, in visit order. */
    int indexAt(int processed) {
        if (processed < 0 || processed >= count) {
            throw new IndexOutOfBoundsException("window holds " + count + " entries, asked for " + processed);
        }
        return start + processed;
    }

    /** Whether this window covers the whole set, so one run completes an iteration. */
    boolean coversEverything() {
        return count >= total;
    }
}
