package com.huidu.farmersdelight.listener;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnifeEnchantFilterTest {

    @Test
    void calculatesAllThreeVanillaStyleOfferCostsFromOneSeed() {
        Random random = new Random(123456789L);

        assertEquals(8, KnifeEnchantFilter.calculateSlotCost(random, 0, 15));
        assertEquals(11, KnifeEnchantFilter.calculateSlotCost(random, 1, 15));
        assertEquals(30, KnifeEnchantFilter.calculateSlotCost(random, 2, 15));
    }

    @Test
    void thirdOfferNeverFallsBelowTwiceTheBookshelfBonus() {
        for (int seed = 0; seed < 200; seed++) {
            assertTrue(KnifeEnchantFilter.calculateSlotCost(new Random(seed), 2, 15) >= 30);
        }
    }

    @Test
    void modifiedLevelUsesItemEnchantabilityAndSeedDeterministically() {
        assertEquals(35, KnifeEnchantFilter.modifyLevel(new Random(987654321L), 30, 15));
        assertEquals(2, KnifeEnchantFilter.modifyLevel(new Random(1L), 1, 1));
    }
}
