package com.huidu.farmersdelight.recipe;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reload spike guard: a pass over N entries must do at most the configured budget per call, exactly N in
 * total, and nothing at all once it was cancelled or after it failed.
 */
class RecipeRegistrationBatchTest {

    private static final int BUDGET = 3;

    @Test
    void oneCallNeverExceedsTheBudget() {
        List<String> registered = new ArrayList<>();
        RecipeRegistrationBatch batch = batch(10, registered);

        assertEquals(BUDGET, batch.run(BUDGET), "a call has to stay inside the budget");
        assertTrue(batch.run(BUDGET) <= BUDGET, "and so does every later one");
        assertEquals(BUDGET * 2, batch.cursor(), "progress is the entries this pass registered");
    }

    @Test
    void everyEntryIsRegisteredExactlyOnce() {
        List<String> registered = new ArrayList<>();
        RecipeRegistrationBatch batch = batch(10, registered);

        int processed = 0;
        while (!batch.isDone()) {
            processed += batch.run(BUDGET);
        }

        assertEquals(10, processed, "the whole pass has to run");
        assertEquals(10, registered.size(), "and not one entry more or less");
        assertEquals(IntStream.range(0, 10).mapToObj(i -> "recipe_" + i).toList(), registered,
                "in order and without duplicates");
        assertEquals(0, batch.run(BUDGET), "a finished batch registers nothing");
    }

    @Test
    void aCancelledBatchRegistersNothingElse() {
        List<String> registered = new ArrayList<>();
        RecipeRegistrationBatch batch = batch(10, registered);
        batch.run(BUDGET);

        batch.cancel();

        assertTrue(batch.isCancelled());
        assertEquals(0, batch.run(BUDGET), "a replaced pass must not register anything more");
        assertEquals(BUDGET, registered.size(), "and it leaves no half-registered entry behind");
    }

    @Test
    void aFailingStepAbortsWithoutSkippingSilently() {
        List<String> registered = new ArrayList<>();
        RecipeRegistrationBatch batch = new RecipeRegistrationBatch(
                IntStream.range(0, 5).mapToObj(i -> "recipe_" + i).toList(), id -> {
                    if (id.equals("recipe_2")) {
                        throw new IllegalStateException("bad recipe");
                    }
                    registered.add(id);
                });

        assertThrows(IllegalStateException.class, () -> batch.run(5), "the failure has to reach the caller");
        assertEquals(List.of("recipe_0", "recipe_1"), registered, "nothing after the failure was registered");
        assertFalse(batch.isDone(), "the caller decides whether to retry or cancel");

        batch.cancel();
        assertEquals(0, batch.run(5), "a cancelled failed pass stays quiet");
    }

    private static RecipeRegistrationBatch batch(int size, List<String> registered) {
        return new RecipeRegistrationBatch(IntStream.range(0, size).mapToObj(i -> "recipe_" + i).toList(),
                registered::add);
    }
}
