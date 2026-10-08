package com.huidu.farmersdelight.recipe;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The content generation parsed recipe entries are cached against.
 *
 *
 * Only the CraftEngine readiness pass knows when CraftEngine's content finished (re)loading, and that is the
 * moment every recipe may parse differently even though the file on disk did not change. The pass advances this
 * counter, so entries parsed against the previous content are never handed out again, while a plain
 * {@code /fd reload} that only re-reads the same files stays in the same generation and can reuse its parses.
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
