package com.huidu.farmersdelight.listener;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackstabListenerTest {

    @Test
    void detectsAttackerBehindTargetOnHorizontalPlane() {
        Vector facingSouth = new Vector(0, 0, 1);

        assertTrue(BackstabListener.isBehind(facingSouth, new Vector(0, 5, -2)));
        assertFalse(BackstabListener.isBehind(facingSouth, new Vector(0, 0, 2)));
        assertFalse(BackstabListener.isBehind(facingSouth, new Vector(2, 0, 0)));
    }

    @Test
    void rejectsCoincidentPositionsAndDetectsBehindWithinThreshold() {
        Vector facing = new Vector(0, 0, 1);

        // Same position → not behind
        assertFalse(BackstabListener.isBehind(facing, new Vector(0, 0, 0)));
        // 45° behind right → dot ≈ -0.707 < -0.5 → behind
        assertTrue(BackstabListener.isBehind(facing, new Vector(1, 0, -1)));
        // 45° in front right → dot ≈ 0.707 → not behind
        assertFalse(BackstabListener.isBehind(facing, new Vector(1, 0, 1)));
    }
}
