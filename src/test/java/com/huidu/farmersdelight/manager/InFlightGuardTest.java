package com.huidu.farmersdelight.manager;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The in-flight guard: one submission per block while its task is outstanding, and a claim that its task never
 * released expires after the timeout so the block is submitted again instead of staying marked forever. Every
 * claim is a token, so a slow task from an expired claim cannot release the claim that replaced it.
 */
class InFlightGuardTest {

    @Test
    void aCompletedMarkerCanBeClaimedAgain() {
        InFlightGuard<String> guard = new InFlightGuard<>();

        long first = guard.tryAdd("pot@1,64,1", 100L);
        assertTrue(first > 0);
        guard.complete("pot@1,64,1", first);

        long second = guard.tryAdd("pot@1,64,1", 101L);
        assertTrue(second > 0, "the task ran, so the next pass may submit again");
        assertFalse(guard.isStale("pot@1,64,1", 101L));
    }

    @Test
    void aMarkerYoungerThanTheTimeoutBlocksASecondSubmission() {
        InFlightGuard<String> guard = new InFlightGuard<>(200L);

        assertTrue(guard.tryAdd("pot@1,64,1", 1_000L) > 0);

        assertFalse(guard.isStale("pot@1,64,1", 1_199L));
        assertEquals(0L, guard.tryAdd("pot@1,64,1", 1_199L), "the task may still run and must not be submitted twice");
    }

    @Test
    void aMarkerOlderThanTheTimeoutIsStaleAndMayBeReclaimed() {
        InFlightGuard<String> guard = new InFlightGuard<>(200L);

        assertTrue(guard.tryAdd("pot@1,64,1", 1_000L) > 0);

        assertTrue(guard.isStale("pot@1,64,1", 1_200L), "the accepted task never released its claim");
        long reclaimed = guard.tryAdd("pot@1,64,1", 1_200L);
        assertTrue(reclaimed > 0, "a stale claim is replaced by the new submission");
        assertFalse(guard.isStale("pot@1,64,1", 1_200L));
        assertEquals(0L, guard.tryAdd("pot@1,64,1", 1_250L), "the fresh claim holds again");
    }

    @Test
    void aLateCompletionDoesNotReleaseANewerClaim() {
        InFlightGuard<String> guard = new InFlightGuard<>(200L);

        long expired = guard.tryAdd("pot@1,64,1", 1_000L);
        assertTrue(expired > 0);
        long replacement = guard.tryAdd("pot@1,64,1", 1_200L);
        assertTrue(replacement > 0, "the expired claim can be replaced");

        guard.complete("pot@1,64,1", expired);

        assertEquals(0L, guard.tryAdd("pot@1,64,1", 1_201L),
                "the late task for the expired claim must not release the replacement claim");
        guard.complete("pot@1,64,1", replacement);
        assertTrue(guard.tryAdd("pot@1,64,1", 1_202L) > 0, "completing the current claim releases it");
    }

    @Test
    void releaseDropsTheMarkerRegardlessOfToken() {
        InFlightGuard<String> guard = new InFlightGuard<>(200L);

        long claim = guard.tryAdd("pot@1,64,1", 1_000L);
        assertTrue(claim > 0);
        guard.release("pot@1,64,1");

        assertFalse(guard.isStale("pot@1,64,1", 1_201L));
        assertTrue(guard.tryAdd("pot@1,64,1", 1_202L) > 0);
    }

    @Test
    void anUnknownKeyIsNotStale() {
        InFlightGuard<String> guard = new InFlightGuard<>();

        assertFalse(guard.isStale("never-seen", 10_000L));
    }

    @Test
    void concurrentSubmissionsClaimTheKeyOnce() throws InterruptedException {
        InFlightGuard<String> guard = new InFlightGuard<>();
        int workers = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(workers);
        AtomicInteger claims = new AtomicInteger();
        List<Thread> threads = new ArrayList<>(workers);
        for (int i = 0; i < workers; i++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    if (guard.tryAdd("stove@1,64,1", 500L) > 0) {
                        claims.incrementAndGet();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            });
            threads.add(worker);
            worker.start();
        }

        start.countDown();
        assertTrue(finished.await(5, TimeUnit.SECONDS), "the claiming threads must finish");
        assertEquals(1, claims.get(), "concurrent passes must not submit the same block twice");
        for (Thread thread : threads) {
            thread.join();
        }
    }

    @Test
    void clearDropsEveryMarker() {
        InFlightGuard<String> guard = new InFlightGuard<>();

        assertTrue(guard.tryAdd("pot@1,64,1", 10L) > 0);
        guard.clear();

        assertFalse(guard.isStale("pot@1,64,1", 10_000L));
        assertTrue(guard.tryAdd("pot@1,64,1", 10_000L) > 0);
    }
}
