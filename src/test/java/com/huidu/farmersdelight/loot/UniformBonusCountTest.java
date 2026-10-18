package com.huidu.farmersdelight.loot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UniformBonusCountTest {

    @Test
    void noFortuneLeavesTheBaseDropUnchanged() {
        assertEquals(1, new UniformBonusCount().apply(1, 0));
    }

    @Test
    void fortuneThreeRollsTheFullUniformRangeInsteadOfAddingThreeFixedItems() {
        UniformBonusCount formula = new UniformBonusCount();
        boolean aboveFour = false;
        for (int sample = 0; sample < 500; sample++) {
            int result = formula.apply(1, 3);
            assertTrue(result >= 1 && result <= 7);
            aboveFour |= result > 4;
        }
        assertTrue(aboveFour);
    }
}
