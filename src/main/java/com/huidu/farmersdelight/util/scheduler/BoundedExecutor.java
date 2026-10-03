package com.huidu.farmersdelight.util.scheduler;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A worker pool that refuses work instead of queueing it without bound.
 *
 *
 * An unbounded queue turns an overloaded pool into late work and rising memory rather than a visible
 * failure, and the async work here is file IO that has to happen eventually but never on the server thread.
 * The rejection policy is ThreadPoolExecutor.AbortPolicy on purpose: CallerRunsPolicy would
 * run the task on whichever region or entity thread submitted it, which is the one thing this pool exists to
 * prevent.
 */
public final class BoundedExecutor {

    private BoundedExecutor() {
    }

    public static ThreadPoolExecutor create(int workers, int capacity, ThreadFactory factory) {
        return new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), factory, new ThreadPoolExecutor.AbortPolicy());
    }
}
