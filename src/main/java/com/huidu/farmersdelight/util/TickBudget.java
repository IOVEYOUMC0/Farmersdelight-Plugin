package com.huidu.farmersdelight.util;

/**
 * The cursor arithmetic behind work that is spread over several ticks: how many entries one slice may consume
 * and where the next slice starts.
 *
 * A slice never runs past the end of the work, and a budget below one is raised to one, so a caller that read a
 * limit of zero out of a config still makes progress instead of arming a slice that does nothing. A cursor built
 * from a stale count is clamped rather than rejected, because the count can change between two slices.
 */
public final class TickBudget {

    private TickBudget() {
    }

    /**
     * One position in a batch of size entries that consumes at most budget of them per slice.
     */
    public record Cursor(int done, int size, int budget) {

        public Cursor {
            size = Math.max(0, size);
            budget = Math.max(1, budget);
            done = Math.min(Math.max(0, done), size);
        }

        /** The first index this slice must not touch: the end of the batch, or done plus the budget. */
        public int sliceEnd() {
            return (int) Math.min((long) size, (long) done + budget);
        }

        /** The cursor the next slice starts from; it advances by at most the budget and always by at least one. */
        public Cursor advance() {
            return new Cursor(sliceEnd(), size, budget);
        }

        /** Whether every entry of the batch has been consumed. */
        public boolean finished() {
            return done >= size;
        }

        /** How many entries are left, counting the one this cursor points at. */
        public int remaining() {
            return size - done;
        }
    }

    /** A cursor at done in a batch of size entries that may consume at most budget of them per slice. */
    public static Cursor at(int done, int size, int budget) {
        return new Cursor(done, size, budget);
    }
}
