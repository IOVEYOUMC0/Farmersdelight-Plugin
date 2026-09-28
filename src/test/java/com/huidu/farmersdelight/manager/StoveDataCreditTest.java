package com.huidu.farmersdelight.manager;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Cooking progress is credited per elapsed game tick, not once per tick-loop visit. */
class StoveDataCreditTest {

    private static final int INTERVAL = 4;
    private static final int CAP = 1200;

    @Test
    void firstCreditUsesTheInterval() {
        StoveData stove = new StoveData(new Location(null, 0, 0, 0), 600);

        assertEquals(INTERVAL, stove.elapsedSinceLastCredit(1000L, INTERVAL, CAP));
    }

    @Test
    void laterCreditsMeasureElapsedTicks() {
        StoveData stove = new StoveData(new Location(null, 0, 0, 0), 600);
        stove.elapsedSinceLastCredit(1000L, INTERVAL, CAP);

        // A stove revisited after eight ticks (budget starvation) credits eight, so a 600-tick recipe still
        // finishes after 600 game ticks instead of 2400.
        assertEquals(8, stove.elapsedSinceLastCredit(1008L, INTERVAL, CAP));
    }

    @Test
    void stalledVisitsAreCapped() {
        StoveData stove = new StoveData(new Location(null, 0, 0, 0), 600);
        stove.elapsedSinceLastCredit(1000L, INTERVAL, CAP);

        assertEquals(CAP, stove.elapsedSinceLastCredit(1000L + CAP + 5000L, INTERVAL, CAP));
    }

    @Test
    void nonAdvancingTicksFallBackToTheInterval() {
        StoveData stove = new StoveData(new Location(null, 0, 0, 0), 600);
        stove.elapsedSinceLastCredit(1000L, INTERVAL, CAP);

        assertEquals(INTERVAL, stove.elapsedSinceLastCredit(1000L, INTERVAL, CAP));
        assertEquals(INTERVAL, stove.elapsedSinceLastCredit(999L, INTERVAL, CAP));
    }
}
