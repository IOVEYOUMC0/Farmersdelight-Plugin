package com.huidu.farmersdelight.api.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyLocksTest {

    @Test
    void equalKeysAnswerTheSameMonitorAndNullIsSupported() {
        KeyLocks locks = new KeyLocks();
        assertSame(locks.lockFor("world:1:64:2"), locks.lockFor(new String("world:1:64:2")));
        assertSame(locks.lockFor(null), locks.lockFor(null));
        assertSame(0, locks.call(null, () -> 0));
    }

    @Test
    void thePoolStaysBoundedHoweverManyKeysAreVisited() {
        KeyLocks locks = new KeyLocks();
        assertEquals(1024, locks.stripeCount());
        Set<Object> monitors = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; i < 100_000; i++) {
            monitors.add(locks.lockFor("world:" + i + ":64:2"));
        }
        assertTrue(monitors.size() > 1);
        assertTrue(monitors.size() <= locks.stripeCount());
    }

    @Test
    void theStripeCountRoundsUpToAPowerOfTwo() {
        assertEquals(1024, new KeyLocks(1000).stripeCount());
        assertEquals(1, new KeyLocks(0).stripeCount());
        assertEquals(1, new KeyLocks(-8).stripeCount());
        assertEquals(64, new KeyLocks(64).stripeCount());
        assertEquals(KeyLocks.MAX_STRIPES, new KeyLocks(Integer.MAX_VALUE).stripeCount());
    }

    @Test
    void oneKeyIsMutuallyExclusiveAcrossThreads() throws Exception {
        KeyLocks locks = new KeyLocks();
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Future<?>> jobs = new ArrayList<>();
            for (int worker = 0; worker < 8; worker++) {
                jobs.add(executor.submit(() -> {
                    try {
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                    for (int i = 0; i < 200; i++) {
                        locks.run("world:1:64:2", () -> {
                            peak.accumulateAndGet(inside.incrementAndGet(), Math::max);
                            Thread.yield();
                            completed.incrementAndGet();
                            inside.decrementAndGet();
                        });
                    }
                }));
            }
            start.countDown();
            for (Future<?> job : jobs) {
                job.get(10, TimeUnit.SECONDS);
            }
        }
        assertEquals(1, peak.get());
        assertEquals(1600, completed.get());
    }
}
