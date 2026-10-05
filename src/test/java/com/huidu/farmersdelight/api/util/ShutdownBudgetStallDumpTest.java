package com.huidu.farmersdelight.api.util;

import com.huidu.farmersdelight.util.scheduler.BlockedThreadDump;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShutdownBudgetStallDumpTest {

    private static final List<BlockedThreadDump.Sample> SNAPSHOT = List.of(
            new BlockedThreadDump.Sample("farmersdelight async pool-1", "WAITING", List.of(
                    "com.huidu.farmersdelight.manager.KegManager.flush(KegManager.java:42)",
                    "java.lang.Thread.run(Thread.java:1583)")));

    private static List<String> observe(ShutdownBudget budget) {
        List<String> reports = new ArrayList<>();
        budget.withStallDump(BlockedThreadDump.toSink(reports::add, () -> SNAPSHOT));
        return reports;
    }

    @Test
    void aPoolThatTerminatesCleanlyNeverDumps() {
        ShutdownBudget budget = ShutdownBudget.ofMillis(2000L, null);
        List<String> reports = observe(budget);
        ExecutorService pool = Executors.newSingleThreadExecutor();

        try {
            assertTrue(budget.awaitTermination("clean pool", pool), "an idle pool terminates inside the budget");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, reports.size(), "a clean drain must not be reported as a stuck shutdown");
    }

    @Test
    void aPoolThatOutlivesTheBudgetDumpsOnceWithTheOwningThreads() {
        ShutdownBudget budget = ShutdownBudget.ofMillis(ShutdownBudget.MIN_TOTAL_MILLIS, null);
        List<String> reports = observe(budget);
        CountDownLatch started = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "farmersdelight async pool-1");
            thread.setDaemon(true);
            return thread;
        });
        pool.execute(() -> {
            started.countDown();
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(20L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertFalse(budget.awaitTermination("farmersdelight async pool", pool), "the worker ignores the wait");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, reports.size(), "a timed-out drain reports exactly once");
        String report = reports.get(0);
        assertTrue(report.contains("did not drain inside the shutdown budget"), report);
        assertTrue(report.contains("farmersdelight async pool-1"), report);
        assertTrue(report.contains("KegManager.flush"), report);
    }

    @Test
    void aBudgetThatWasAlreadySpentNeverDumps() {
        ShutdownBudget budget = ShutdownBudget.ofMillis(ShutdownBudget.MIN_TOTAL_MILLIS, null);
        List<String> reports = observe(budget);
        ExecutorService pool = Executors.newSingleThreadExecutor();

        try {
            budget.step("slow", () -> {
                try {
                    Thread.sleep(ShutdownBudget.MIN_TOTAL_MILLIS + 200L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertFalse(budget.awaitTermination("worker pool", pool), "a spent budget cannot wait for the pool");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0, reports.size(), "a wait that never ran is not a stuck pool");
    }

    @Test
    void stepsAndFinishedWaitsNeverTouchTheDump() {
        ShutdownBudget budget = ShutdownBudget.ofMillis(2000L, null);
        List<String> reports = observe(budget);
        ExecutorService pool = Executors.newSingleThreadExecutor();

        try {
            for (int index = 0; index < 200; index++) {
                assertTrue(budget.step("step " + index, () -> {
                }));
            }
            assertTrue(budget.awaitTermination("idle pool", pool));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, reports.size(), "the dump belongs to the timeout path only");
    }
}
