package com.huidu.farmersdelight.listener;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackstabListenerTest {

    @Test
    void detectsAttackerBehindTargetOnHorizontalPlane() {
        Vector facingSouth = new Vector(0, 0, 1);

        assertTrue(BackstabListener.isBehind(facingSouth, new Vector(0, 5, -2), -0.5D, 0.000001D));
        assertFalse(BackstabListener.isBehind(facingSouth, new Vector(0, 0, 2), -0.5D, 0.000001D));
        assertFalse(BackstabListener.isBehind(facingSouth, new Vector(2, 0, 0), -0.5D, 0.000001D));
    }

    @Test
    void rejectsCoincidentPositionsAndHonorsConfiguredThreshold() {
        Vector facing = new Vector(0, 0, 1);

        assertFalse(BackstabListener.isBehind(facing, new Vector(0, 0, 0), -0.5D, 0.01D));
        assertTrue(BackstabListener.isBehind(facing, new Vector(1, 0, -1), -0.5D, 0.01D));
        assertFalse(BackstabListener.isBehind(facing, new Vector(1, 0, -1), -0.8D, 0.01D));
    }
}
