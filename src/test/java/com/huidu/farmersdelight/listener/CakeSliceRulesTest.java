package com.huidu.farmersdelight.listener;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CakeSliceRulesTest {

    @Test
    void aLightableCandleCakeDoesNotRequireCakeBlockData() {
        for (Material material : Material.values()) {
            if (CakeSliceRules.candleFor(material) == null) continue;
            org.bukkit.block.data.Lightable data = (org.bukkit.block.data.Lightable)
                    java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[]{org.bukkit.block.data.Lightable.class}, (proxy, method, args) -> {
                                if (method.getName().equals("getMaterial")) return material;
                                throw new AssertionError(method.getName());
                            });
            assertEquals(0, CakeSliceRules.bites(data), material.toString());
            assertEquals(1, CakeSliceRules.nextBites(CakeSliceRules.bites(data), true));
        }
    }

    @Test
    void candleCakesReturnTheirCandle() {
        assertEquals(Material.CANDLE, CakeSliceRules.candleFor(Material.CANDLE_CAKE));
        assertEquals(Material.WHITE_CANDLE, CakeSliceRules.candleFor(Material.WHITE_CANDLE_CAKE));
        assertEquals(Material.BLACK_CANDLE, CakeSliceRules.candleFor(Material.BLACK_CANDLE_CAKE));
        assertNull(CakeSliceRules.candleFor(Material.CAKE));
    }

    @Test
    void onlyVanillaCakesAreTargets() {
        assertTrue(CakeSliceRules.target(Material.CAKE, false));
        assertTrue(CakeSliceRules.target(Material.RED_CANDLE_CAKE, false));
        assertFalse(CakeSliceRules.target(Material.STONE, false));
        assertFalse(CakeSliceRules.target(Material.CAKE, true),
                "a CustomBlock state at that position means the block is not a vanilla cake");
    }

    @Test
    void aPlainCakeAdvancesOneBiteAndTheLastSliceRemovesIt() {
        assertEquals(1, CakeSliceRules.nextBites(0, false));
        assertEquals(6, CakeSliceRules.nextBites(5, false));
        assertEquals(-1, CakeSliceRules.nextBites(6, false));
    }

    @Test
    void aCandleCakeAlwaysBecomesAPlainCakeWithOneBite() {
        assertEquals(1, CakeSliceRules.nextBites(0, true));
        assertEquals(1, CakeSliceRules.nextBites(4, true));
        assertEquals(1, CakeSliceRules.nextBites(6, true));
    }

    @Test
    void biteCountsOutsideTheVanillaRangeAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> CakeSliceRules.nextBites(-1, false));
        assertThrows(IllegalArgumentException.class, () -> CakeSliceRules.nextBites(7, false));
    }

    @Test
    void theSecondHandOfTheSameTickIsADuplicate() {
        UUID world = UUID.randomUUID();
        CakeSliceRules.Click first = new CakeSliceRules.Click(world, 1, 64, 2, 100, false);
        assertTrue(CakeSliceRules.duplicateHand(first,
                new CakeSliceRules.Click(world, 1, 64, 2, 100, true)));
        assertFalse(CakeSliceRules.duplicateHand(first,
                new CakeSliceRules.Click(world, 1, 64, 2, 101, true)), "a later tick is a new slice");
        assertFalse(CakeSliceRules.duplicateHand(first,
                new CakeSliceRules.Click(world, 1, 64, 2, 100, false)), "the same hand is not a duplicate");
        assertFalse(CakeSliceRules.duplicateHand(null, first));
    }
}
