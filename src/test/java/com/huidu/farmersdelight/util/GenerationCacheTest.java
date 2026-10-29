package com.huidu.farmersdelight.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GenerationCacheTest {
    @Test
    void hitsDoNotReloadAndReturnedValuesStayCached() {
        GenerationCache<String, String> cache = new GenerationCache<>(2);
        AtomicInteger loads = new AtomicInteger();
        assertEquals("old", cache.get(1, "item", key -> { loads.incrementAndGet(); return "old"; }));
        assertEquals("old", cache.get(1, "item", key -> { loads.incrementAndGet(); return "unexpected"; }));
        assertEquals(1, loads.get());
        assertEquals("new", cache.get(2, "item", key -> "new"));
    }

    @Test
    void anOldReaderCannotReplaceTheNewGeneration() {
        GenerationCache<String, String> cache = new GenerationCache<>(2);
        assertEquals("current", cache.get(2, "item", key -> "current"));
        assertEquals("stale", cache.get(1, "item", key -> "stale"));
        assertEquals("current", cache.get(2, "item", key -> "unexpected"));
    }

    @Test
    void reloadDetachesAnInFlightLoaderAtTheSameRegistryVersion() throws Exception {
        GenerationCache<String, String> cache = new GenerationCache<>(2);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<String> old = executor.submit(() -> cache.get(1, "item", key -> {
                entered.countDown();
                await(release);
                return "old definition";
            }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                cache.invalidate();
                assertEquals("new definition", cache.get(1, "item", key -> "new definition"));
            } finally {
                release.countDown();
            }
            assertEquals("old definition", old.get(5, TimeUnit.SECONDS));
            assertEquals("new definition", cache.get(1, "item", key -> "unexpected"));
        }
    }

    @Test
    void concurrentMissesCannotExceedTheAdmissionLimit() throws Exception {
        GenerationCache<Integer, String> cache = new GenerationCache<>(1);
        CountDownLatch entered = new CountDownLatch(8);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Future<String>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                int id = i;
                calls.add(executor.submit(() -> cache.get(1, id, key -> {
                    entered.countDown();
                    await(release);
                    return "loaded";
                })));
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
            for (Future<String> call : calls) assertEquals("loaded", call.get(5, TimeUnit.SECONDS));
            AtomicInteger cached = new AtomicInteger();
            for (int i = 0; i < 8; i++) {
                if ("loaded".equals(cache.get(1, i, key -> "not admitted"))) cached.incrementAndGet();
            }
            assertEquals(1, cached.get());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("latch timeout");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}
