package com.huidu.farmersdelight.recipe;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks the content generation used by parsed recipe caches.
 * CraftEngine readiness advances it after content reload; a file-only reload can reuse unchanged parses.
 */
public final class RecipeContentEpoch {

    private static final AtomicLong CURRENT = new AtomicLong();

    private RecipeContentEpoch() {
    }

    /** The generation new parses are cached against. */
    public static long current() {
        return CURRENT.get();
    }

    /** Starts a new generation: nothing parsed against the previous one may be reused. */
    public static long advance() {
        return CURRENT.incrementAndGet();
    }
}
