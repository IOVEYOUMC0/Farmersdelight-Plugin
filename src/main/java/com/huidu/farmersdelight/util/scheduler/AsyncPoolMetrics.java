package com.huidu.farmersdelight.util.scheduler;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The counters behind the async pool's diagnostics, and the one place a task is handed to that pool.
 *
 * The pool itself is untouched: same size, same bounded queue, same abort policy, and a refusal still reaches
 * the caller as the exception the executor threw. What is added is one increment on the way in, one on the way
 * out inside the task's own finally, and one when the pool refuses the task, so the numbers describe the pool
 * without changing what it does.
 *
 * A task the pool never ran because it was force-stopped is counted as refused: it was handed over and did not
 * run, and counting it keeps in flight at zero once the pool is gone instead of leaving a number that never
 * falls.
 */
final class AsyncPoolMetrics {

    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();

    /** Hands the task over; a refusal propagates to the caller, which still owns whatever the task was for. */
    void submit(ThreadPoolExecutor pool, Runnable task) {
        Runnable tracked = tracked(task);
        try {
            pool.execute(tracked);
        } catch (RejectedExecutionException refused) {
            rejected.incrementAndGet();
            throw refused;
        }
    }

    /** Hands the task over, reporting a refusal as false. */
    boolean trySubmit(ThreadPoolExecutor pool, Runnable task) {
        try {
            submit(pool, task);
            return true;
        } catch (RejectedExecutionException refused) {
            return false;
        }
    }

    /** Reads one snapshot: the three counters, then the queue depth they are read against. */
    AsyncSnapshot snapshot(ThreadPoolExecutor pool) {
        return new AsyncSnapshot(submitted.get(), completed.get(), rejected.get(), queueDepth(pool));
    }

    /** Stops the pool at once and counts the tasks it drops, so the reading after a stop still adds up. */
    void forcedShutdown(ThreadPoolExecutor pool) {
        List<Runnable> dropped = pool.shutdownNow();
        if (!dropped.isEmpty()) {
            rejected.addAndGet(dropped.size());
        }
    }

    /** Counts what a drain dropped, once the pool reports that it has terminated. */
    void accountTerminated(ThreadPoolExecutor pool) {
        if (!pool.isTerminated()) {
            return;
        }
        long remaining = submitted.get() - completed.get() - rejected.get();
        if (remaining > 0L) {
            rejected.addAndGet(remaining);
        }
    }

    private Runnable tracked(Runnable task) {
        Objects.requireNonNull(task, "task");
        submitted.incrementAndGet();
        return () -> {
            try {
                task.run();
            } finally {
                completed.incrementAndGet();
            }
        };
    }

    private static int queueDepth(ThreadPoolExecutor pool) {
        return pool.getQueue().size() + pool.getActiveCount();
    }
}
