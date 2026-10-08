package com.huidu.farmersdelight.util.scheduler;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The async pool counters. The pool under test is the real bounded pool, small enough that the queue can be
 * filled on purpose, so a refusal is the pool's own AbortPolicy and not a stand-in for it.
 */
class AsyncPoolMetricsTest {

    private static final int WORKERS = 1;

    @Test
    void theNormalPathCountsEveryTaskOnce() throws Exception {
        ThreadPoolExecutor pool = BoundedExecutor.create(WORKERS, 8, Thread::new);
        AsyncPoolMetrics metrics = new AsyncPoolMetrics();
        CountDownLatch ran = new CountDownLatch(3);
        try {
            for (int i = 0; i < 3; i++) {
                assertTrue(metrics.trySubmit(pool, ran::countDown), "the queue has room");
            }
            assertTrue(ran.await(5, TimeUnit.SECONDS), "the pool runs what it accepted");
            awaitCompleted(pool, 3);

            AsyncSnapshot snapshot = metrics.snapshot(pool);
            assertEquals(3L, snapshot.submitted());
            assertEquals(3L, snapshot.completed());
            assertEquals(0L, snapshot.rejected());
            assertEquals(0L, snapshot.inFlight());
            assertEquals(0, snapshot.queueDepth());
            assertTrue(snapshot.consistent());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aRefusedTaskIsCountedAndTheRefusalStillReachesTheCaller() throws Exception {
        ThreadPoolExecutor pool = BoundedExecutor.create(WORKERS, 1, Thread::new);
        AsyncPoolMetrics metrics = new AsyncPoolMetrics();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch workerInside = new CountDownLatch(1);
        AtomicBoolean refusedTaskRan = new AtomicBoolean();
        try {
            // One task occupies the only worker, one fills the single queue slot, and the third cannot be kept.
            metrics.submit(pool, () -> {
                workerInside.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(workerInside.await(5, TimeUnit.SECONDS), "the worker is inside the first task");
            metrics.submit(pool, () -> { });

            RejectedExecutionException refusal = assertThrows(RejectedExecutionException.class,
                    () -> metrics.submit(pool, () -> refusedTaskRan.set(true)),
                    "a refusal has to reach the caller, which still owns the task");
            assertNotNull(refusal.getMessage(), "the executor's own refusal is what the caller sees");
            assertFalse(metrics.trySubmit(pool, () -> refusedTaskRan.set(true)), "the tolerated call reports it");

            AsyncSnapshot refused = metrics.snapshot(pool);
            assertEquals(4L, refused.submitted(), "two accepted, two refused");
            assertEquals(2L, refused.rejected(), "refusals are counted, not swallowed");
            assertEquals(2L, refused.inFlight(), "one running, one queued");
            assertTrue(refused.consistent());
            assertFalse(refusedTaskRan.get(), "a refused task never runs");

            release.countDown();
            awaitCompleted(pool, 2);
            AsyncSnapshot drained = metrics.snapshot(pool);
            assertEquals(0L, drained.inFlight());
            assertEquals(drained.submitted(), drained.completed() + drained.rejected());
            assertEquals(2L, drained.completed());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void theSnapshotAgreesWithItselfWhileWorkIsHandedOverConcurrently() throws Exception {
        ThreadPoolExecutor pool = BoundedExecutor.create(2, 4, Thread::new);
        AsyncPoolMetrics metrics = new AsyncPoolMetrics();
        AtomicReference<String> contradiction = new AtomicReference<>();
        AtomicInteger accepted = new AtomicInteger();
        int submitters = 4;
        int each = 60;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        try {
            for (int t = 0; t < submitters; t++) {
                Thread submitter = new Thread(() -> {
                    try {
                        start.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < each; i++) {
                        if (metrics.trySubmit(pool, () -> { })) {
                            accepted.incrementAndGet();
                        }
                    }
                }, "submitter-" + t);
                threads.add(submitter);
                submitter.start();
            }
            Thread reader = new Thread(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < 400; i++) {
                    AsyncSnapshot snapshot = metrics.snapshot(pool);
                    if (!snapshot.consistent() || snapshot.inFlight() < 0
                            || snapshot.inFlight() != snapshot.submitted() - snapshot.completed()
                            - snapshot.rejected()) {
                        contradiction.compareAndSet(null, snapshot.toString());
                    }
                }
            }, "reader");
            reader.start();
            start.countDown();
            for (Thread thread : threads) {
                thread.join(10_000L);
            }
            reader.join(10_000L);
        } finally {
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }

        assertEquals(null, contradiction.get(), "no reading may contradict itself");
        AsyncSnapshot finalSnapshot = metrics.snapshot(pool);
        assertTrue(finalSnapshot.consistent());
        assertEquals(0L, finalSnapshot.inFlight(), "everything handed over either finished or was refused");
        assertEquals(finalSnapshot.submitted(), finalSnapshot.completed() + finalSnapshot.rejected());
        assertTrue(finalSnapshot.submitted() > 0L, "the run handed work over");
        assertTrue(finalSnapshot.rejected() > 0L, "a queue of four refuses some of it");
        assertEquals(accepted.get() + finalSnapshot.rejected(), finalSnapshot.submitted());
    }

    @Test
    void readingTheSnapshotLeavesThePoolAlone() throws Exception {
        ThreadPoolExecutor pool = BoundedExecutor.create(WORKERS, 4, Thread::new);
        AsyncPoolMetrics metrics = new AsyncPoolMetrics();
        try {
            int core = pool.getCorePoolSize();
            int max = pool.getMaximumPoolSize();
            int capacity = pool.getQueue().remainingCapacity();
            Class<?> handler = pool.getRejectedExecutionHandler().getClass();
            Class<?> queueType = pool.getQueue().getClass();

            for (int i = 0; i < 200; i++) {
                assertNotNull(metrics.snapshot(pool));
            }

            assertEquals(core, pool.getCorePoolSize(), "reading does not resize the pool");
            assertEquals(max, pool.getMaximumPoolSize());
            assertEquals(capacity, pool.getQueue().remainingCapacity(), "reading does not consume the queue");
            assertSame(handler, pool.getRejectedExecutionHandler().getClass(), "the abort policy stays in place");
            assertSame(queueType, pool.getQueue().getClass());

            CountDownLatch ran = new CountDownLatch(1);
            assertTrue(metrics.trySubmit(pool, ran::countDown), "the pool still accepts work after a reading");
            assertTrue(ran.await(5, TimeUnit.SECONDS), "and still runs it");
            awaitCompleted(pool, 1);
            assertEquals(1L, metrics.snapshot(pool).completed());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aForceStoppedPoolCountsWhatItDropped() throws Exception {
        ThreadPoolExecutor pool = BoundedExecutor.create(WORKERS, 4, Thread::new);
        AsyncPoolMetrics metrics = new AsyncPoolMetrics();
        CountDownLatch workerInside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        metrics.submit(pool, () -> {
            workerInside.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(workerInside.await(5, TimeUnit.SECONDS), "the worker is inside the first task");
        for (int i = 0; i < 3; i++) {
            assertTrue(metrics.trySubmit(pool, () -> { }));
        }
        assertEquals(4L, metrics.snapshot(pool).inFlight(), "one running, three queued");

        metrics.forcedShutdown(pool);
        release.countDown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        AsyncSnapshot stopped = metrics.snapshot(pool);
        assertEquals(4L, stopped.submitted());
        assertEquals(1L, stopped.completed(), "the running task still finished");
        assertEquals(3L, stopped.rejected(), "the three the queue gave back are counted as refused");
        assertEquals(0L, stopped.inFlight(), "nothing is left in flight once the pool is gone");
        assertTrue(stopped.consistent());

        metrics.accountTerminated(pool);
        assertEquals(stopped, metrics.snapshot(pool), "a terminated pool has nothing left to count");
    }

    private static void awaitCompleted(ThreadPoolExecutor pool, long expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (pool.getCompletedTaskCount() >= expected) {
                return;
            }
            Thread.sleep(5L);
        }
        throw new AssertionError("only " + pool.getCompletedTaskCount() + " of " + expected + " tasks finished");
    }
}
