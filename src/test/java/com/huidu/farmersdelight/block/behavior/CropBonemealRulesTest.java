package com.huidu.farmersdelight.block.behavior;

import net.momirealms.craftengine.bukkit.block.behavior.CropBlockBehavior;
import net.momirealms.craftengine.bukkit.block.behavior.StemBlockBehavior;
import net.momirealms.craftengine.core.block.behavior.BonemealableBlock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bone meal gating for the custom crops. A mature plant must stop being a valid target once it has nothing
 * left to grow, otherwise both hand use and dispensers consume bone meal with no effect. Every crop
 * behavior must also expose the bonemeal interface: the dispenser path only reaches CraftEngine behaviors
 * through that interface.
 */
class CropBonemealRulesTest {

    @Test
    void immatureGroundTomatoesAcceptBoneMeal() {
        assertTrue(TomatoVineBlockBehavior.canFeedGroundTomatoes(0, 3, false, false));
        assertTrue(TomatoVineBlockBehavior.canFeedGroundTomatoes(2, 3, false, false));
    }

    @Test
    void matureGroundTomatoesAcceptBoneMealOnlyWhileTheVineCanAdvance() {
        assertFalse(TomatoVineBlockBehavior.canFeedGroundTomatoes(3, 3, false, false),
                "a fully grown tomato with no rope above must not consume bone meal");
        assertTrue(TomatoVineBlockBehavior.canFeedGroundTomatoes(3, 3, true, false),
                "a hanging crop that can still age keeps the target valid");
        assertTrue(TomatoVineBlockBehavior.canFeedGroundTomatoes(3, 3, false, true),
                "a rope above keeps the target valid");
    }

    @Test
    void matureHangingTomatoesAcceptBoneMealOnlyWhileTheVineCanClimb() {
        assertTrue(TomatoVineBlockBehavior.canFeedHangingTomatoes(0, 3, false));
        assertTrue(TomatoVineBlockBehavior.canFeedHangingTomatoes(3, 3, true));
        assertFalse(TomatoVineBlockBehavior.canFeedHangingTomatoes(3, 3, false),
                "a hanging tomato that cannot climb must not consume bone meal");
    }

    @Test
    void everyCustomCropBehaviorAdvertisesTheBonemealInterfaceForDispensers() {
        assertTrue(BonemealableBlock.class.isAssignableFrom(TomatoVineBlockBehavior.class));
        assertTrue(BonemealableBlock.class.isAssignableFrom(WildPlantBlockBehavior.class));
        assertTrue(BonemealableBlock.class.isAssignableFrom(WildRiceBlockBehavior.class));
        assertTrue(BonemealableBlock.class.isAssignableFrom(TallCropBlockBehavior.class));
        assertTrue(BonemealableBlock.class.isAssignableFrom(MushroomColonyBehavior.class));
        // The CraftEngine behaviors behind onions, cabbages, the rich-soil vanilla crop replacements and
        // the melon/pumpkin stems must stay bonemealable for the dispenser path to reach them.
        assertTrue(BonemealableBlock.class.isAssignableFrom(CropBlockBehavior.class));
        assertTrue(BonemealableBlock.class.isAssignableFrom(StemBlockBehavior.class));
    }
}
