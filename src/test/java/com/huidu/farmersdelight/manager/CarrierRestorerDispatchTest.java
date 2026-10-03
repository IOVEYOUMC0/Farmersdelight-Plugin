package com.huidu.farmersdelight.manager;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the decisions the carrier restorer's maintenance pass makes before it touches the world: which
 * slice of the tracked displays one pass verifies, and whether the loaded chunks may be enumerated at all.
 *
 *
 * Both are pure so they can be checked without a server, which matters because everything either decision
 * leads to — reading a chunk, reading an entity — only runs on the region that owns it.
 */
class CarrierRestorerDispatchTest {

    @Test
    void anEmptyTrackedSetProducesAnEmptyWindow() {
        SweepWindow window = CarrierRestorer.verificationWindow(0, 5, 4);

        assertEquals(0, window.count());
        assertEquals(0, window.start());
        assertEquals(0, window.nextCursor());
    }

    @Test
    void aPassVerifiesTheNextEntriesAfterTheCursor() {
        SweepWindow window = CarrierRestorer.verificationWindow(10, 4, 4);

        assertEquals(4, window.start());
        assertEquals(4, window.count());
        assertEquals(8, window.nextCursor(), "the next pass continues where this one stopped");
    }

    @Test
    void aWindowNeverRunsPastTheEndOfTheSet() {
        SweepWindow window = CarrierRestorer.verificationWindow(10, 8, 4);

        assertEquals(8, window.start());
        assertEquals(2, window.count(), "only the entries left after the cursor");
        assertEquals(0, window.nextCursor(), "landing on the end wraps to the beginning");
    }

    @Test
    void aCursorPastTheEndRestartsAtTheBeginning() {
        SweepWindow window = CarrierRestorer.verificationWindow(10, 100, 4);

        assertEquals(0, window.start());
        assertEquals(4, window.count());
        assertEquals(4, window.nextCursor());
    }

    @Test
    void aNegativeCursorIsReducedModuloTheTrackedCount() {
        // The old cursor arithmetic used floorMod, so -3 over 10 entries is entry 7; the reduction keeps that
        // behaviour for a counter that can only be compared against a set that shrank.
        SweepWindow window = CarrierRestorer.verificationWindow(10, -3, 4);

        assertEquals(7, window.start());
        assertEquals(3, window.count());
        assertEquals(0, window.nextCursor());
    }

    @Test
    void aPassNeverExceedsTheTrackedCountOrTheBudget() {
        SweepWindow window = CarrierRestorer.verificationWindow(3, 1, 8);

        assertEquals(1, window.start());
        assertEquals(2, window.count());

        // A budget below one still verifies one entry per pass, matching the configured floor.
        assertEquals(1, CarrierRestorer.verificationWindow(10, 0, 0).count());
    }

    @Test
    void loadedChunksAreOnlyEnumeratedWhereNoRegionOwnsThem() {
        assertTrue(CarrierRestorer.mayEnumerateLoadedChunks(false),
                "a single-threaded platform can walk every world's loaded chunks");
        assertFalse(CarrierRestorer.mayEnumerateLoadedChunks(true),
                "on Folia the queue is filled by the chunk load event instead");
    }
}
