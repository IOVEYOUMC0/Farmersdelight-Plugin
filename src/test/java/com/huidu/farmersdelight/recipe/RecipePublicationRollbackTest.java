package com.huidu.farmersdelight.recipe;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The restore actions of one pass run back to front, once, and one component that cannot be put back does not
 * stop the others: the caller is already handling the failure that caused the rollback.
 */
class RecipePublicationRollbackTest {

    @Test
    void restoresRunInReversePublicationOrder() {
        List<String> order = new ArrayList<>();
        RecipePublicationRollback rollback = new RecipePublicationRollback(List.of(
                () -> order.add("pot"),
                () -> order.add("board"),
                () -> order.add("index")));

        assertEquals(List.of(), rollback.restore(), "a clean rollback reports no failures");

        assertEquals(List.of("index", "board", "pot"), order,
                "the derived index is rebuilt before the sets it reads are put back");
        assertTrue(rollback.isRestored());
    }

    @Test
    void restoringTwiceDoesNothingTheSecondTime() {
        List<String> order = new ArrayList<>();
        RecipePublicationRollback rollback = new RecipePublicationRollback(List.of(() -> order.add("pot")));

        rollback.restore();
        assertEquals(List.of(), rollback.restore());

        assertEquals(List.of("pot"), order, "a pass is only put back once");
    }

    @Test
    void aFailingRestoreDoesNotStopTheOthersAndIsReported() {
        List<String> order = new ArrayList<>();
        RecipePublicationRollback rollback = new RecipePublicationRollback(List.of(
                () -> order.add("pot"),
                () -> {
                    order.add("board");
                    throw new IllegalStateException("board restore failed");
                },
                () -> order.add("index")));

        List<Throwable> failures = rollback.restore();

        assertEquals(List.of("index", "board", "pot"), order, "the remaining restores still run");
        assertEquals(1, failures.size());
        assertEquals("board restore failed", failures.getFirst().getMessage());
    }

    @Test
    void anEmptyRollbackReportsNothing() {
        RecipePublicationRollback rollback = new RecipePublicationRollback(List.of());

        assertEquals(List.of(), rollback.restore());
        assertTrue(rollback.isRestored(), "an empty rollback still counts as restored");
    }
}
