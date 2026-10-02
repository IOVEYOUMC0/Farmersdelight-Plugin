package com.huidu.farmersdelight.util.scheduler;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The async pool carries file IO that must never run on the server thread. Two properties matter: a full
 * queue is refused rather than growing without bound, and the refusal is visible to the caller instead of
 * being executed inline (which is exactly what CallerRunsPolicy would have done).
 */
class BoundedExecutorTest {

    @Test
    void refusesWorkWhenTheQueueIsFull() throws InterruptedException {
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ThreadPoolExecutor pool = BoundedExecutor.create(1, 1, Thread::new);
        try {
            pool.execute(() -> {
                occupied.countDown();
                await(release);
            });
            assertTrue(occupied.await(5, TimeUnit.SECONDS), "the single worker never started");

            pool.execute(() -> { }); // fills the single queue slot
            assertThrows(RejectedExecutionException.class, () -> pool.execute(() -> { }),
                    "the worker thread must not run refused work inline");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void refusedWorkIsReportedToTheCallerAndNeverRuns() throws InterruptedException {
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger ran = new AtomicInteger();
        ThreadPoolExecutor pool = BoundedExecutor.create(1, 1, Thread::new);
        try {
            pool.execute(() -> {
                occupied.countDown();
                await(release);
            });
            assertTrue(occupied.await(5, TimeUnit.SECONDS));
            pool.execute(ran::incrementAndGet);

            boolean accepted = true;
            try {
                pool.execute(ran::incrementAndGet);
            } catch (RejectedExecutionException refused) {
                accepted = false;
            }
            assertFalse(accepted, "a full queue has to refuse rather than queue");

            release.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(1, ran.get(), "only the queued task may have run");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
