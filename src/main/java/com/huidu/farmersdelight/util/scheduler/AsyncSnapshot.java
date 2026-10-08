package com.huidu.farmersdelight.util.scheduler;

/**
 * One reading of the async pool: how much work it was handed, how much it finished, how much it refused and
 * how much is waiting for it.
 *
 * In flight is derived from the three counters rather than kept beside them, so a reading can never
 * contradict itself and can never report a negative amount of work: a task that was handed over is either
 * finished or counted as refused, and a task the pool dropped when it was force-stopped is counted as refused
 * as well.
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
