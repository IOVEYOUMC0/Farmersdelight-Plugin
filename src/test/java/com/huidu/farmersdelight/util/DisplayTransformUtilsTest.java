package com.huidu.farmersdelight.util;

import com.huidu.farmersdelight.util.compat.DisplayTransformUtils;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DisplayTransformUtilsTest {

    @Test
    void yawValuesMatchFarmersDelightRenderers() {
        assertEquals(0.0F, DisplayTransformUtils.cuttingBoardYaw(BlockFace.NORTH));
        assertEquals(180.0F, DisplayTransformUtils.cuttingBoardYaw(BlockFace.SOUTH));
        assertEquals(-90.0F, DisplayTransformUtils.cuttingBoardYaw(BlockFace.EAST));
        assertEquals(90.0F, DisplayTransformUtils.cuttingBoardYaw(BlockFace.WEST));

        assertEquals(180.0F, DisplayTransformUtils.skilletYaw(BlockFace.NORTH));
        assertEquals(0.0F, DisplayTransformUtils.skilletYaw(BlockFace.SOUTH));
        assertEquals(90.0F, DisplayTransformUtils.skilletYaw(BlockFace.EAST));
        assertEquals(-90.0F, DisplayTransformUtils.skilletYaw(BlockFace.WEST));

        assertEquals(0.0F, DisplayTransformUtils.stoveYaw(BlockFace.NORTH));
        assertEquals(180.0F, DisplayTransformUtils.stoveYaw(BlockFace.SOUTH));
        assertEquals(-90.0F, DisplayTransformUtils.stoveYaw(BlockFace.EAST));
        assertEquals(90.0F, DisplayTransformUtils.stoveYaw(BlockFace.WEST));
    }

    @Test
    void stoveSlotOffsetsRotateLikeOriginalPoseStack() {
        assertArrayEquals(new double[]{0.3D, 1.02D, 0.2D},
                DisplayTransformUtils.stoveSlotOffset(0.3D, 1.02D, 0.2D, BlockFace.NORTH), 0.0001D);
        assertArrayEquals(new double[]{-0.2D, 1.02D, 0.3D},
                DisplayTransformUtils.stoveSlotOffset(0.3D, 1.02D, 0.2D, BlockFace.EAST), 0.0001D);
        assertArrayEquals(new double[]{-0.3D, 1.02D, -0.2D},
                DisplayTransformUtils.stoveSlotOffset(0.3D, 1.02D, 0.2D, BlockFace.SOUTH), 0.0001D);
        assertArrayEquals(new double[]{0.2D, 1.02D, -0.3D},
                DisplayTransformUtils.stoveSlotOffset(0.3D, 1.02D, 0.2D, BlockFace.WEST), 0.0001D);
    }
}
