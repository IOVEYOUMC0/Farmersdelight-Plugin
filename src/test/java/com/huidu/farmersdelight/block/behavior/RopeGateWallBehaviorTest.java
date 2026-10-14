package com.huidu.farmersdelight.block.behavior;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RopeGateWallBehaviorTest {
    @Test
    void northSouthGatesConnectOnlyToEastWestWalls() {
        for (BlockFace facing : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH}) {
            assertTrue(RopeGateWallBehavior.connects(facing, BlockFace.EAST));
            assertTrue(RopeGateWallBehavior.connects(facing, BlockFace.WEST));
            assertFalse(RopeGateWallBehavior.connects(facing, BlockFace.NORTH));
            assertFalse(RopeGateWallBehavior.connects(facing, BlockFace.SOUTH));
        }
    }

    @Test
    void eastWestGatesConnectOnlyToNorthSouthWalls() {
        for (BlockFace facing : new BlockFace[]{BlockFace.EAST, BlockFace.WEST}) {
            assertTrue(RopeGateWallBehavior.connects(facing, BlockFace.NORTH));
            assertTrue(RopeGateWallBehavior.connects(facing, BlockFace.SOUTH));
            assertFalse(RopeGateWallBehavior.connects(facing, BlockFace.EAST));
            assertFalse(RopeGateWallBehavior.connects(facing, BlockFace.WEST));
        }
    }
}
