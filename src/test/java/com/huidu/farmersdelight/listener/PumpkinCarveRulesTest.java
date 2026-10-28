package com.huidu.farmersdelight.listener;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PumpkinCarveRulesTest {

    @Test
    void aHorizontalClickUsesThatSide() {
        assertEquals(BlockFace.NORTH, PumpkinCarveRules.carvedFacing(BlockFace.NORTH, BlockFace.SOUTH));
        assertEquals(BlockFace.EAST, PumpkinCarveRules.carvedFacing(BlockFace.EAST, BlockFace.NORTH));
    }

    @Test
    void clickingTheTopOrBottomFacesThePlayer() {
        // The carved face turns back towards the player, which is what the mod's useOn does.
        assertEquals(BlockFace.NORTH, PumpkinCarveRules.carvedFacing(BlockFace.UP, BlockFace.SOUTH));
        assertEquals(BlockFace.SOUTH, PumpkinCarveRules.carvedFacing(BlockFace.DOWN, BlockFace.NORTH));
        assertEquals(BlockFace.WEST, PumpkinCarveRules.carvedFacing(BlockFace.UP, BlockFace.EAST));
    }

    @Test
    void aMissingFaceStillProducesAValidDirection() {
        assertEquals(BlockFace.NORTH, PumpkinCarveRules.carvedFacing(null, null));
        assertEquals(BlockFace.WEST, PumpkinCarveRules.carvedFacing(BlockFace.UP, BlockFace.EAST));
    }
}
