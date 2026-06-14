package com.huidu.farmersdelight.block.behavior;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CuttingBoardStoredItemPoseTest {

    @Test
    void flatPoseIsNotCarved() {
        assertFalse(CuttingBoardStoredItemPose.FLAT.itemCarved());
        assertSame(CuttingBoardStoredItemPose.FLAT, CuttingBoardStoredItemPose.fromCarved(false));
    }

    @Test
    void insertedToolPoseKeepsCarvedState() {
        assertTrue(CuttingBoardStoredItemPose.INSERTED_TOOL.itemCarved());
        assertSame(CuttingBoardStoredItemPose.INSERTED_TOOL, CuttingBoardStoredItemPose.fromCarved(true));
    }
}
