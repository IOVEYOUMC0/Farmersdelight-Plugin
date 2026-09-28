package com.huidu.farmersdelight.block.behavior;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VanillaCropGrowthTest {

    private static float[] soil(float factor) {
        float[] factors = new float[VanillaCropGrowth.SOIL_FACTOR_COUNT];
        for (int index = 0; index < factors.length; index++) {
            factors[index] = factor;
        }
        return factors;
    }

    private static float[] singleSoil(float factor) {
        float[] factors = new float[VanillaCropGrowth.SOIL_FACTOR_COUNT];
        factors[4] = factor;
        return factors;
    }

    @Test
    void speedMatchesTheVanillaFarmlandFormula() {
        // A lone crop on dry farmland: 1 + 1.
        assertEquals(2.0F, VanillaCropGrowth.growthSpeed(singleSoil(1.0F), false, false, false));
        // A lone crop on moist farmland: 1 + 3.
        assertEquals(4.0F, VanillaCropGrowth.growthSpeed(singleSoil(3.0F), false, false, false));
        // A full 3x3 of dry farmland: 1 + 1 + 8 * 0.25.
        assertEquals(4.0F, VanillaCropGrowth.growthSpeed(soil(1.0F), false, false, false));
        // A full 3x3 of moist farmland: 1 + 3 + 8 * 0.75.
        assertEquals(10.0F, VanillaCropGrowth.growthSpeed(soil(3.0F), false, false, false));
        // No soil at all still starts from 1, so the chance stays defined.
        assertEquals(1.0F, VanillaCropGrowth.growthSpeed(singleSoil(0.0F), false, false, false));
    }

    @Test
    void rowsAndDiagonalsHalveTheSpeed() {
        assertEquals(2.0F, VanillaCropGrowth.growthSpeed(soil(1.0F), true, true, false));
        assertEquals(2.0F, VanillaCropGrowth.growthSpeed(soil(1.0F), false, false, true));
        // A horizontal neighbour alone is a row, not a cross, so nothing is halved.
        assertEquals(4.0F, VanillaCropGrowth.growthSpeed(soil(1.0F), true, false, false));
    }

    @Test
    void chanceMatchesTheVanillaRandomTickProbability() {
        assertEquals(1.0F / 13.0F, VanillaCropGrowth.chance(2.0F), 1.0E-6F);
        assertEquals(1.0F / 7.0F, VanillaCropGrowth.chance(4.0F), 1.0E-6F);
        assertEquals(1.0F / 3.0F, VanillaCropGrowth.chance(10.0F), 1.0E-6F);
        assertEquals(1.0F / 26.0F, VanillaCropGrowth.chance(1.0F), 1.0E-6F);
    }

    @Test
    void rollComparesAgainstTheChance() {
        assertTrue(VanillaCropGrowth.passes(2.0F, 1.0F / 14.0F));
        assertFalse(VanillaCropGrowth.passes(2.0F, 1.0F / 12.0F));
    }
}
