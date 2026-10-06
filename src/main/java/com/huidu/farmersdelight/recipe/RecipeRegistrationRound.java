package com.huidu.farmersdelight.recipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One recipe reload round: every source a single loadRecipes() call has to register, in the order the reload
 * resolved them, drained across ticks under one shared per-tick budget.
 *
 *
 * Holding the sources in one round is what makes a reload one queue instead of one batch per source: when a
 * source is exhausted the remaining budget goes to the next one, and a source that follows another never
 * cancels what the first has not reached. Each source's own tail runs the moment that source is exhausted; the
 * round's tail belongs to the whole reload and is run by the driver after the last entry.
 */
public final class RecipeRegistrationRound {

    /**
     * One source of the round: the entries to register, the work each entry does, and the reporting that waits
     * until this source has no entries left. A source with no ids still runs its tail.
     */
    public record Segment(List<String> ids, RecipeRegistrationBatch.Step step, Runnable onDone) {

        public Segment {
            ids = List.copyOf(Objects.requireNonNull(ids, "ids"));
            Objects.requireNonNull(step, "step");
            if (onDone == null) {
                onDone = () -> { };
            }
        }
    }

    private final List<RecipeRegistrationBatch> batches;
    private final List<Runnable> tails;
    private final int budget;
    private final int total;
    private int index;
    private boolean cancelled;

    public RecipeRegistrationRound(List<Segment> segments, int budget) {
        Objects.requireNonNull(segments, "segments");
        // At least one entry per call, so a misconfigured 0 still makes progress.
        this.budget = Math.max(1, budget);
        this.batches = new ArrayList<>(segments.size());
        this.tails = new ArrayList<>(segments.size());
        int size = 0;
        for (Segment segment : segments) {
            this.batches.add(new RecipeRegistrationBatch(segment.ids(), segment.step()));
            this.tails.add(segment.onDone());
            size += segment.ids().size();
        }
        this.total = size;
    }

    /** Total entries this round has to register. */
    public int size() {
        return total;
    }

    /** Entries already registered; cursor()/size() is the "i/N" progress a reload report shows. */
    public int cursor() {
        int registered = 0;
        for (RecipeRegistrationBatch batch : batches) {
            registered += batch.cursor();
        }
        return registered;
    }

    public boolean isDone() {
        return cancelled || index >= batches.size();
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Registers at most the round budget in total, moving on to the next source when one is exhausted, so a
     * budget spent exactly at a source boundary never leaves the rest of the round waiting for a later tick.
     */
    public void run() {
        if (cancelled) {
            return;
        }
        int remaining = budget;
        while (remaining > 0 && index < batches.size()) {
            RecipeRegistrationBatch current = batches.get(index);
            remaining -= current.run(remaining);
            if (!current.isDone()) {
                return;
            }
            index++;
            tails.get(index - 1).run();
        }
    }

    /**
     * Drops every entry that has not been registered yet: a replaced reload and plugin disable both leave no
     * half-finished round holding recipe state, and a dropped source's tail never runs.
     */
    public void cancel() {
        this.cancelled = true;
        for (int i = index; i < batches.size(); i++) {
            batches.get(i).cancel();
        }
        this.index = batches.size();
    }
}
