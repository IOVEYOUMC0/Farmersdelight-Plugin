package com.huidu.farmersdelight.manager;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the rotating-sweep arithmetic used by the cooking-pot cleanup.
 *
 *
 * The important property is not the individual numbers but coverage: successive runs must visit every
 * tracked entry, and no entry may be skipped because the set shrank under a stale cursor. That was the risk
 * when the Paper cleanup gained a budget.
 */
class SweepWindowTest {

    @Test
    void emptySetProducesAnEmptyWindowAndResetsTheCursor() {
        SweepWindow window = SweepWindow.of(0, 7, 128);

        assertEquals(0, window.count());
        assertEquals(0, window.total());
        assertEquals(0, window.nextCursor(), "a stale offset must not survive an empty set");
    }

    @Test
    void negativeSizeIsTreatedAsEmpty() {
        assertEquals(0, SweepWindow.of(-5, 3, 128).count());
    }

    @Test
    void aSetWithinBudgetIsSweptInOnePass() {
        SweepWindow window = SweepWindow.of(10, 0, 128);

        assertEquals(0, window.start());
        assertEquals(10, window.count());
        assertEquals(0, window.nextCursor(), "finishing the set restarts at the beginning");
        assertTrue(window.coversEverything());
    }

    @Test
    void aSetExactlyAtBudgetTakesOnePass() {
        SweepWindow window = SweepWindow.of(128, 0, 128);

        assertEquals(128, window.count());
        assertTrue(window.coversEverything());
        assertEquals(0, window.nextCursor());
    }

    @Test
    void anOversizedSetIsSplitIntoBudgetSizedRuns() {
        SweepWindow window = SweepWindow.of(1000, 0, 128);

        assertEquals(0, window.start());
        assertEquals(128, window.count());
        assertEquals(128, window.nextCursor());
        assertFalse(window.coversEverything());
    }

    @Test
    void successiveRunsVisitEveryEntryExactlyOncePerIteration() {
        int total = 1000;
        int budget = 128;
        boolean[] visited = new boolean[total];

        int cursor = 0;
        int runs = 0;
        while (true) {
            SweepWindow window = SweepWindow.of(total, cursor, budget);
            assertTrue(window.count() <= budget, "a run must never exceed the budget");
            for (int processed = 0; processed < window.count(); processed++) {
                int index = window.indexAt(processed);
                assertFalse(visited[index], "entry " + index + " visited twice in one iteration");
                assertTrue(index >= 0 && index < total);
                visited[index] = true;
            }
            runs++;
            cursor = window.nextCursor();
            if (cursor == 0) {
                break;
            }
            assertTrue(runs <= total, "rotation did not terminate");
        }

        for (int index = 0; index < total; index++) {
            assertTrue(visited[index], "entry " + index + " was never visited");
        }
        assertEquals(8, runs, "1000 entries at 128 per run is 8 runs (7 full + 1 partial)");
    }

    @Test
    void aShrunkSetClampsAStaleCursorInsteadOfSkipping() {
        // Cursor 500 came from a 1000-entry set; only 10 entries are tracked now.
        SweepWindow window = SweepWindow.of(10, 500, 128);

        assertEquals(0, window.start(), "an out-of-range cursor restarts at the beginning");
        assertEquals(10, window.count());
        assertEquals(0, window.nextCursor());
    }

    @Test
    void aPartialLastRunWrapsToTheBeginning() {
        SweepWindow window = SweepWindow.of(300, 256, 128);

        assertEquals(256, window.start());
        assertEquals(44, window.count(), "only the remaining 44 entries fit");
        assertEquals(0, window.nextCursor());
    }

    @Test
    void aNonPositiveBudgetStillMakesProgress() {
        SweepWindow window = SweepWindow.of(300, 0, 0);

        assertEquals(1, window.count(), "a zero budget must not stall the sweep");
        assertEquals(1, window.nextCursor());
    }

    @Test
    void aNegativeCursorStartsAtTheBeginning() {
        assertEquals(0, SweepWindow.of(300, -3, 128).start());
    }

    @Test
    void indexAtRejectsOutOfWindowPositions() {
        SweepWindow window = SweepWindow.of(300, 0, 128);

        assertThrows(IndexOutOfBoundsException.class, () -> window.indexAt(128));
        assertThrows(IndexOutOfBoundsException.class, () -> window.indexAt(-1));
    }

    @Test
    void theWindowStartsWhereItIsAskedTo() {
        SweepWindow window = SweepWindow.of(300, 128, 128);

        assertEquals(128, window.start());
        assertEquals(128, window.indexAt(0), "the first visited entry is the cursor");
        assertEquals(255, window.indexAt(127), "the window is contiguous and never wraps mid-run");
    }
}
