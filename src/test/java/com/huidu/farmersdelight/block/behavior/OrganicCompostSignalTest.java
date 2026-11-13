package com.huidu.farmersdelight.block.behavior;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The organic compost comparator mirrors the upstream block: a fresh heap is strongest and a heap one step from
 * rich soil is weakest.
 */
class OrganicCompostSignalTest {

    @Test
    void freshHeapIsStrongestAndLastStageIsWeakest() {
        assertEquals(8, OrganicCompostBlockBehavior.signalFor(7, 0));
        assertEquals(1, OrganicCompostBlockBehavior.signalFor(7, 7));
    }

    @Test
    void signalDecreasesWithEachStage() {
        int previous = Integer.MAX_VALUE;
        for (int stage = 0; stage <= 7; stage++) {
            int signal = OrganicCompostBlockBehavior.signalFor(7, stage);
            assertTrue(signal < previous, "stage " + stage + " has to be weaker than the previous stage");
            previous = signal;
        }
    }

    @Test
    void customRangeAndOutliersStayInsideTheVanillaRange() {
        assertEquals(4, OrganicCompostBlockBehavior.signalFor(3, 0));
        assertEquals(1, OrganicCompostBlockBehavior.signalFor(3, 3));
        assertEquals(8, OrganicCompostBlockBehavior.signalFor(7, -5), "negative stages behave like stage 0");
        assertEquals(1, OrganicCompostBlockBehavior.signalFor(7, 99), "stages past the range behave like the last stage");
        assertEquals(15, OrganicCompostBlockBehavior.signalFor(99, 0), "the output never exceeds a comparator's range");
    }

    @Test
    void behaviorOverridesBothComparatorHooks() throws Exception {
        assertTrue(java.lang.reflect.Modifier.isPublic(
                        OrganicCompostBlockBehavior.class
                                .getMethod("hasAnalogOutputSignal", Object.class, Object[].class)
                                .getModifiers()),
                "CE only sees a public hook");
        assertTrue(java.lang.reflect.Modifier.isPublic(
                        OrganicCompostBlockBehavior.class
                                .getMethod("getAnalogOutputSignal", Object.class, Object[].class)
                                .getModifiers()),
                "CE only sees a public hook");
    }
}
