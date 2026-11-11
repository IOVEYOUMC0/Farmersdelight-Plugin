package com.huidu.farmersdelight.block.behavior;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rich soil boosts the plant above it, except for crops planted from below (cave vines); those are boosted from
 * the block below instead. The upstream mod encodes that in the planted_from_below tag.
 */
class RichSoilDirectionTest {

    @Test
    void ordinaryCropsAreBoostedFromAboveOnly() {
        assertTrue(RichSoilBlockBehavior.boostsAbove(false));
        assertFalse(RichSoilBlockBehavior.boostsAbove(true));
    }

    @Test
    void hangingCropsAreBoostedFromBelowOnly() {
        assertTrue(RichSoilBlockBehavior.boostsBelow(true));
        assertFalse(RichSoilBlockBehavior.boostsBelow(false));
    }
}
