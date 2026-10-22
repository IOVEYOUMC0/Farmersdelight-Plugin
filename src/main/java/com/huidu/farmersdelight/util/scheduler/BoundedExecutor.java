package com.huidu.farmersdelight.util.scheduler;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A worker pool that refuses work instead of queueing it without bound.
 */
public final class BoundedExecutor {

    private BoundedExecutor() {
    }

    public static ThreadPoolExecutor create(int workers, int capacity, ThreadFactory factory) {
        return new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), factory, new ThreadPoolExecutor.AbortPolicy());
    }
}
