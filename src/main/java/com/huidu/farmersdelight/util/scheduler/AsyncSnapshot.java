package com.huidu.farmersdelight.util.scheduler;

/**
 * One reading of the async pool: how much work it was handed, how much it finished, how much it refused and
 * how much is waiting for it.
 */
public record AsyncSnapshot(long submitted, long completed, long rejected, int queueDepth) {

    /** Handed over and not finished: the queue and the workers together. */
    public long inFlight() {
        return Math.max(0L, submitted - completed - rejected);
    }

    /** True when the counters and the queue depth can describe one state of the pool. */
    public boolean consistent() {
        return submitted >= 0L && completed >= 0L && rejected >= 0L && queueDepth >= 0
                && completed + rejected <= submitted;
    }
}
