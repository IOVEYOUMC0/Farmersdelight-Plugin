package com.huidu.farmersdelight.visual;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Completion accounting for one display sync pass.
 *
 * Two things have to finish before the batch may flush: the pass handing out its region tasks, and every task it
 * handed out reporting back. A task reports whether it ran or the scheduler refused it - a refused task never
 * runs and never calls a retired callback, so without that report the batch would wait forever.
 *
 * The two counters are separate on purpose: a task reporting before the pass finished handing out work must not
 * be mistaken for the pass itself finishing.
 */
final class DisplaySyncBatch {

    private final AtomicInteger tasks = new AtomicInteger();
    private final AtomicBoolean passReleased = new AtomicBoolean();
    private final AtomicBoolean flushed = new AtomicBoolean();

    /** Announces one region task; every announcement must be answered by exactly one done(). */
    void expect() {
        tasks.incrementAndGet();
    }

    /** Reports one announced task. Returns true when this report completed the batch. */
    boolean done() {
        tasks.updateAndGet(current -> current > 0 ? current - 1 : 0);
        return ready();
    }

    /** Reports that the pass will hand out no further work. Returns true when it completed the batch. */
    boolean release() {
        passReleased.set(true);
        return ready();
    }

    private boolean ready() {
        return passReleased.get() && tasks.get() == 0 && flushed.compareAndSet(false, true);
    }

    int outstandingTasks() {
        return tasks.get();
    }

    boolean flushed() {
        return flushed.get();
    }
}
