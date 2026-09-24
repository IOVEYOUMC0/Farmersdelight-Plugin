package com.huidu.farmersdelight.block.behavior;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cooking pot's progress bar is owned by the pot tick: it must restart when the dish being cooked changes
 * (including when the swapped-in recipe only matches after a tick with no valid recipe) and must wind back at
 * the mod's two-ticks-per-tick rate when the pot is not cooking.
 */
class CookingPotProgressTest {

    @Test
    void anotherDishRestartsTheBar() {
        assertTrue(CookingPotBlockEntity.isDifferentDish("farmersdelight:beef_stew", "farmersdelight:fish_stew"));
    }

    @Test
    void theSameDishResumesWhereItStopped() {
        assertFalse(CookingPotBlockEntity.isDifferentDish("farmersdelight:beef_stew", "farmersdelight:beef_stew"));
        // First match after placement / load: there is no earlier dish to restart from.
        assertFalse(CookingPotBlockEntity.isDifferentDish(null, "farmersdelight:beef_stew"));
    }

    @Test
    void cookingAdvancesAndCapsAtTheDuration() {
        assertEquals(30, CookingPotBlockEntity.advanceOrDecayProgress(20, 200, 10, true));
        assertEquals(200, CookingPotBlockEntity.advanceOrDecayProgress(195, 200, 10, true));
    }

    @Test
    void anIdlePotRegressesTwiceAsFastAndStopsAtZero() {
        assertEquals(80, CookingPotBlockEntity.advanceOrDecayProgress(100, 200, 10, false));
        assertEquals(0, CookingPotBlockEntity.advanceOrDecayProgress(5, 200, 10, false));
        assertEquals(7, CookingPotBlockEntity.advanceOrDecayProgress(7, 200, 0, false));
    }
}
