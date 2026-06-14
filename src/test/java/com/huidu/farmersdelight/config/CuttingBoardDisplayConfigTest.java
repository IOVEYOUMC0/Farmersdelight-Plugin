package com.huidu.farmersdelight.config;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class CuttingBoardDisplayConfigTest {

    @Test
    void mergeAddsPositionOffsetsButReplacesTransformValues() {
        CuttingBoardDisplayConfig.DisplayOverride base = new CuttingBoardDisplayConfig.DisplayOverride(
                null,
                CuttingBoardDisplayConfig.DisplayStyle.AUTO,
                new Vector3f(0.1F, 0.2F, 0.3F),
                new Vector3f(1.0F, 1.0F, 1.0F),
                new Vector3f(10.0F, 20.0F, 30.0F),
                new Vector3f(0.5F, 0.5F, 0.5F)
        );
        CuttingBoardDisplayConfig.DisplayOverride override = new CuttingBoardDisplayConfig.DisplayOverride(
                "minecraft:stone",
                CuttingBoardDisplayConfig.DisplayStyle.BLOCK,
                new Vector3f(0.4F, -0.1F, 0.2F),
                new Vector3f(2.0F, 2.0F, 2.0F),
                new Vector3f(90.0F, 180.0F, 0.0F),
                new Vector3f(0.75F, 0.75F, 0.75F)
        );

        CuttingBoardDisplayConfig.DisplayOverride merged = base.merge(override);

        assertEquals("minecraft:stone", merged.displayItemId());
        assertEquals(CuttingBoardDisplayConfig.DisplayStyle.BLOCK, merged.style());
        assertVectorEquals(new Vector3f(0.5F, 0.1F, 0.5F), merged.offset());
        assertVectorEquals(override.translation(), merged.translation());
        assertVectorEquals(override.rotationDegrees(), merged.rotationDegrees());
        assertVectorEquals(override.scale(), merged.scale());
        assertNotSame(override.scale(), merged.scale());
    }

    private static void assertVectorEquals(Vector3f expected, Vector3f actual) {
        assertEquals(expected.x(), actual.x(), 0.0001F);
        assertEquals(expected.y(), actual.y(), 0.0001F);
        assertEquals(expected.z(), actual.z(), 0.0001F);
    }
}
