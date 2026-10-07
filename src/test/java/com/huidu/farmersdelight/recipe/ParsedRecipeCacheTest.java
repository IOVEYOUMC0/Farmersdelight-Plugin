package com.huidu.farmersdelight.recipe;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parse store hands back what it parsed while the content generation and the entry's own values are
 * unchanged, and parses again the moment either of them changes. It is bounded, so a server that keeps editing
 * recipes cannot grow it without end, and it counts what it did.
 */
class ParsedRecipeCacheTest {

    @Test
    void theSameInputsAreParsedOnce() {
        ParsedRecipeCache<String> cache = new ParsedRecipeCache<>();
        AtomicInteger calls = new AtomicInteger();

        String first = cache.parse(7L, "file.yml", "soup", "values-a", () -> "parsed-" + calls.incrementAndGet());
        String second = cache.parse(7L, "file.yml", "soup", "values-a", () -> "parsed-" + calls.incrementAndGet());

        assertSame(first, second, "the same key has to hand back the value it parsed");
        assertEquals(1, calls.get());
        assertEquals(1, cache.reused());
        assertEquals(1, cache.parsed());
    }

    @Test
    void aNewGenerationNeverReuses() {
        ParsedRecipeCache<String> cache = new ParsedRecipeCache<>();
        AtomicInteger calls = new AtomicInteger();

        String first = cache.parse(7L, "file.yml", "soup", "values-a", () -> "parsed-" + calls.incrementAndGet());
        String second = cache.parse(8L, "file.yml", "soup", "values-a", () -> "parsed-" + calls.incrementAndGet());

        assertTrue(first != second, "a new content generation has to parse again");
        assertEquals(2, calls.get());
        assertEquals(0, cache.reused());
    }

    @Test
    void changedValuesAreParsedAgainInTheSameGeneration() {
        ParsedRecipeCache<String> cache = new ParsedRecipeCache<>();
        AtomicInteger calls = new AtomicInteger();

        String first = cache.parse(7L, "file.yml", "soup", "values-a", () -> "parsed-" + calls.incrementAndGet());
        String second = cache.parse(7L, "file.yml", "soup", "values-b", () -> "parsed-" + calls.incrementAndGet());

        assertTrue(first != second, "a changed entry has to parse again");
        assertEquals(2, calls.get());
        assertEquals(0, cache.reused());
    }

    @Test
    void anotherEntryOrSourceIsNotReused() {
        ParsedRecipeCache<String> cache = new ParsedRecipeCache<>();
        AtomicInteger calls = new AtomicInteger();

        cache.parse(7L, "file.yml", "soup", "values-a", () -> "parsed-" + calls.incrementAndGet());
        cache.parse(7L, "file.yml", "stew", "values-a", () -> "parsed-" + calls.incrementAndGet());
        cache.parse(7L, "pack.yml", "soup", "values-a", () -> "parsed-" + calls.incrementAndGet());

        assertEquals(3, calls.get(), "the key has to carry the source and the entry's path too");
        assertEquals(3, cache.size());
    }

    @Test
    void overTheLimitTheOldestEntryIsEvictedAndResultsStayCorrect() {
        ParsedRecipeCache<String> cache = new ParsedRecipeCache<>(2);
        AtomicInteger calls = new AtomicInteger();
        String soup = cache.parse(7L, "file.yml", "soup", "values", () -> "soup-" + calls.incrementAndGet());
        String stew = cache.parse(7L, "file.yml", "stew", "values", () -> "stew-" + calls.incrementAndGet());
        String pie = cache.parse(7L, "file.yml", "pie", "values", () -> "pie-" + calls.incrementAndGet());

        assertEquals(3, calls.get());
        assertEquals(1, cache.evicted(), "the oldest entry goes once the limit is reached");
        assertEquals(2, cache.size());
        assertEquals(pie, cache.parse(7L, "file.yml", "pie", "values",
                () -> "again-" + calls.incrementAndGet()), "the newest entry is still reused");
        assertEquals(3, calls.get(), "reusing the newest entry parses nothing");
        assertEquals("again-4", cache.parse(7L, "file.yml", "soup", "values",
                () -> "again-" + calls.incrementAndGet()), "the evicted entry parses again");
        assertEquals(4, calls.get(), "only the evicted entry is parsed again");
        assertEquals("soup-1", soup);
        assertEquals("stew-2", stew);
        assertEquals("pie-3", pie);
    }

    @Test
    void concurrentLookupsParseOnceAndStayConsistent() throws Exception {
        ParsedRecipeCache<String> cache = new ParsedRecipeCache<>();
        AtomicInteger calls = new AtomicInteger();
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        String[] values = new String[threads];
        for (int index = 0; index < threads; index++) {
            int slot = index;
            Thread thread = new Thread(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                    values[slot] = cache.parse(7L, "file.yml", "soup", "values", () -> {
                        calls.incrementAndGet();
                        return "parsed";
                    });
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            thread.start();
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));

        for (String value : values) {
            assertEquals("parsed", value);
        }
        assertEquals(1, calls.get(), "one entry is parsed once however many callers ask for it");
        assertEquals(threads - 1, cache.reused());
    }

    @Test
    void theCacheStartsEmptyAndTheLimitHasToBeUsable() {
        ParsedRecipeCache<String> cache = new ParsedRecipeCache<>();
        assertEquals(-1L, cache.generation());
        assertEquals(0, cache.size());
        cache.clear();
        assertEquals(0, cache.size());

        boolean refused = false;
        try {
            new ParsedRecipeCache<>(0);
        } catch (IllegalArgumentException expected) {
            refused = true;
        }
        assertTrue(refused, "a cache without room for one entry is a mistake");
    }
}
