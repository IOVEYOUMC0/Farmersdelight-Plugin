package com.huidu.farmersdelight.recipe;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The queue itself: several sources of one reload drain as a single round under one shared budget, each source
 * is registered completely even when a later source follows it, and a replaced round drops whatever it had not
 * reached.
 *
 * These are the shapes the managers rely on, written against the real round: a source that cancels its
 * predecessor, or a budget that is spent per source instead of per round, turns them red.
 */
class RecipeRegistrationRoundTest {

    private static final int BUDGET = 3;

    // The plugin's own file is followed by pack sections, and the file is longer than the budget: no source may
    // lose its remainder because the next one arrived.
    @Test
    void everySourceOfTheRoundIsRegisteredCompletely() {
        List<String> registered = new ArrayList<>();
        RecipeRegistrationRound round = twoSources(10, registered);

        while (!round.isDone()) {
            round.run();
        }

        List<String> expected = new ArrayList<>(ids("a", 10));
        expected.addAll(ids("b", 10));
        assertEquals(expected, registered, "both sources, in order, once each");
        assertEquals(20, round.cursor(), "the cursor reaches the whole round");
    }

    // One call spends the round budget, no matter how many sources it crosses: a budget reset per source would
    // register more than the configured entries in one tick (the spike the sharding exists to remove).
    @Test
    void oneCallNeverExceedsTheRoundBudgetEvenAcrossSources() {
        List<String> registered = new ArrayList<>();
        RecipeRegistrationRound round = new RecipeRegistrationRound(List.of(
                new RecipeRegistrationRound.Segment(ids("a", 2), registered::add, null),
                new RecipeRegistrationRound.Segment(ids("b", 2), registered::add, null),
                new RecipeRegistrationRound.Segment(ids("c", 10), registered::add, null)), BUDGET);

        round.run();

        assertEquals(List.of("a0", "a1", "b0"), registered,
                "the leftover budget of an exhausted source goes to the next one");
        assertFalse(round.isDone());

        round.run();

        assertEquals(6, registered.size(), "the next call continues where the round stopped");
        assertFalse(round.isDone());
    }

    // A source's tail is the reporting that has to wait for that source; it must not wait for the whole round.
    @Test
    void aSourceTailRunsAsSoonAsThatSourceIsExhausted() {
        List<String> events = new ArrayList<>();
        RecipeRegistrationRound round = new RecipeRegistrationRound(List.of(
                new RecipeRegistrationRound.Segment(ids("a", 2), id -> events.add("step " + id),
                        () -> events.add("tail a")),
                new RecipeRegistrationRound.Segment(ids("b", 2), id -> events.add("step " + id),
                        () -> events.add("tail b"))), BUDGET);

        round.run();

        assertEquals(List.of("step a0", "step a1", "tail a", "step b0"), events,
                "the exhausted source reports before the next source continues");
    }

    // An empty file or section still reports, and it neither spends the budget nor stalls the sources after it.
    @Test
    void anEmptySourceRunsItsTailAndDoesNotStallTheRound() {
        List<String> events = new ArrayList<>();
        RecipeRegistrationRound round = new RecipeRegistrationRound(List.of(
                new RecipeRegistrationRound.Segment(List.of(), id -> events.add("step"), () -> events.add("tail empty")),
                new RecipeRegistrationRound.Segment(ids("b", 2), id -> events.add("step " + id),
                        () -> events.add("tail b"))), BUDGET);

        round.run();

        assertEquals(List.of("tail empty", "step b0", "step b1", "tail b"), events,
                "an empty source costs nothing and is passed through");
        assertTrue(round.isDone());
    }

    @Test
    void aCancelledRoundRegistersNothingElseAndRunsNoTail() {
        List<String> registered = new ArrayList<>();
        List<String> tails = new ArrayList<>();
        RecipeRegistrationRound round = new RecipeRegistrationRound(List.of(
                new RecipeRegistrationRound.Segment(ids("a", 2), registered::add, () -> tails.add("tail a")),
                new RecipeRegistrationRound.Segment(ids("b", 2), registered::add, () -> tails.add("tail b"))), BUDGET);
        round.run();
        assertEquals(List.of("a0", "a1", "b0"), registered);

        round.cancel();

        assertTrue(round.isCancelled());
        assertTrue(round.isDone());
        assertEquals(4, round.cursor(), "everything not registered counts as dropped");
        assertEquals(List.of("tail a"), tails, "a source that had finished still reported");
        round.run();
        assertEquals(List.of("a0", "a1", "b0"), registered, "a cancelled round registers nothing more");
        assertEquals(List.of("tail a"), tails, "and a dropped source never reports");
    }

    @Test
    void anEmptyRoundIsDoneImmediately() {
        RecipeRegistrationRound round = new RecipeRegistrationRound(List.of(), BUDGET);

        assertTrue(round.isDone(), "nothing to register means the round is over before it starts");
        assertEquals(0, round.size());
        round.run();
        assertEquals(0, round.cursor());
    }

    // The caller publishes from the round's tail, so a finished round must never hand it another entry later:
    // a further run of a retired round is a no-op.
    @Test
    void aFinishedRoundRegistersNothingOnLaterRuns() {
        List<String> registered = new ArrayList<>();
        RecipeRegistrationRound round = twoSources(4, registered);
        while (!round.isDone()) {
            round.run();
        }
        List<String> published = List.copyOf(registered);

        round.run();
        round.run();

        assertEquals(published, registered, "a finished round registers nothing more");
        assertEquals(8, round.cursor());
    }

    @Test
    void progressCountsEveryEntryOfEverySource() {
        RecipeRegistrationRound round = twoSources(10, new ArrayList<>());

        assertEquals(0, round.cursor());
        assertEquals(20, round.size(), "N is the whole round");
        round.run();
        assertEquals(BUDGET, round.cursor(), "and the cursor counts across the source boundary");
        while (!round.isDone()) {
            round.run();
        }
        assertEquals(20, round.cursor());
    }

    private static RecipeRegistrationRound twoSources(int size, List<String> registered) {
        return new RecipeRegistrationRound(List.of(
                new RecipeRegistrationRound.Segment(ids("a", size), registered::add, null),
                new RecipeRegistrationRound.Segment(ids("b", size), registered::add, null)), BUDGET);
    }

    private static List<String> ids(String prefix, int size) {
        return IntStream.range(0, size).mapToObj(i -> prefix + i).toList();
    }
}
