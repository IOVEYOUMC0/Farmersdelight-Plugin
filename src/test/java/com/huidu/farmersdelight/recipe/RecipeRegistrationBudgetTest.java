package com.huidu.farmersdelight.recipe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The budget a load runs under: the load that has nothing published yet runs whole, so the readiness pass
 * that follows it in the same tick sees the complete recipe set; every later load keeps the configured
 * per-tick budget.
 */
class RecipeRegistrationBudgetTest {

    private static final int CONFIGURED = 32;

    @Test
    void theFirstLoadIsNotSliced() {
        assertEquals(RecipeRegistrationBudget.WHOLE_LOAD, RecipeRegistrationBudget.forLoad(0, CONFIGURED));
        assertTrue(RecipeRegistrationBudget.forLoad(0, CONFIGURED) > 1000,
                "a first load has to cover the whole file, not one slice of it");
    }

    @Test
    void aLoadWithAPublishedSetKeepsTheConfiguredBudget() {
        assertEquals(CONFIGURED, RecipeRegistrationBudget.forLoad(28, CONFIGURED));
        assertEquals(CONFIGURED, RecipeRegistrationBudget.forLoad(112, CONFIGURED));
    }

    @Test
    void aMisconfiguredBudgetStillMakesProgress() {
        assertEquals(1, RecipeRegistrationBudget.forLoad(5, 0));
        assertEquals(1, RecipeRegistrationBudget.forLoad(5, -3));
        assertEquals(RecipeRegistrationBudget.WHOLE_LOAD, RecipeRegistrationBudget.forLoad(0, 0));
    }
}
