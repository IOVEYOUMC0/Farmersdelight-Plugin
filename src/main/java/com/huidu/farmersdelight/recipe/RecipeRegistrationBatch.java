package com.huidu.farmersdelight.recipe;

import java.util.List;

/**
 * Splits one recipe rebuild/registration pass over many entries into per-tick batches.
 *
 *
 * Rebuilding every recipe entry in a single tick is what produced the reported TPS spike on
 * /fd reload recipes. This is the resumable half of the fix: the round keeps the batch, calls
 * run(int) while it still has budget, on the thread that drives the round, and the batch never
 * does more than the configured budget. Nothing here is asynchronous, and nothing here touches published state
 * on its own — the caller registers entries through Step.
 *
 *
 * The single-active-batch rule (a reload that arrives mid-pass cancels the running batch and starts a fresh
 * one, so entries are never registered twice) belongs to the caller, which is why cancel() exists and
 * makes every later run(int) a no-op.
 */
public final class RecipeRegistrationBatch {

    /** Registers one entry on the calling thread; throwing aborts the pass with no half-registered entry. */
    public interface Step {

        void apply(String recipeId);
    }

    private final List<String> ids;
    private final Step step;
    private int cursor;
    private boolean cancelled;

    public RecipeRegistrationBatch(List<String> ids, Step step) {
        this.ids = List.copyOf(ids);
        this.step = step;
    }

    /** Total entries this pass has to register. */
    public int size() {
        return ids.size();
    }

    /** Entries already registered; cursor()/size() is the "i/N" progress the report shows. */
    public int cursor() {
        return cursor;
    }

    public boolean isDone() {
        return cursor >= ids.size();
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Registers at most budget entries (at least one, so a misconfigured 0 still makes progress) and
     * reports how many it did. A cancelled batch never registers anything.
     */
    public int run(int budget) {
        if (cancelled) {
            return 0;
        }
        int limit = Math.max(1, budget);
        int processed = 0;
        while (processed < limit && cursor < ids.size()) {
            step.apply(ids.get(cursor));
            cursor++;
            processed++;
        }
        return processed;
    }

    /**
     * Drops everything that has not been registered yet: used when a reload is replaced mid-pass and on plugin
     * disable, so no half-finished pass is left holding recipe state.
     */
    public void cancel() {
        cancelled = true;
        cursor = ids.size();
    }
}
