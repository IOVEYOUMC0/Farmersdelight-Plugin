package com.huidu.farmersdelight.util;

import com.huidu.farmersdelight.api.block.FarmersDelightStation;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CuttingBoardBlockBehavior;
import com.huidu.farmersdelight.block.behavior.SkilletBlockBehavior;
import com.huidu.farmersdelight.block.behavior.StoveCookingBlockBehavior;
import net.momirealms.craftengine.core.block.ImmutableBlockState;

/** Resolves a CraftEngine block state to the public station type used by the api events. */
public final class StationTypes {

    private StationTypes() {
    }

    /** The station type of the state, or null when the block is not one of the four built-in stations. */
    public static FarmersDelightStation of(ImmutableBlockState state) {
        if (state == null) {
            return null;
        }
        if (CustomBlockUtils.hasBehavior(state, CookingPotBlockBehavior.class)) {
            return FarmersDelightStation.COOKING_POT;
        }
        if (CustomBlockUtils.hasBehavior(state, CuttingBoardBlockBehavior.class)) {
            return FarmersDelightStation.CUTTING_BOARD;
        }
        if (CustomBlockUtils.hasBehavior(state, SkilletBlockBehavior.class)) {
            return FarmersDelightStation.SKILLET;
        }
        if (CustomBlockUtils.hasBehavior(state, StoveCookingBlockBehavior.class)) {
            return FarmersDelightStation.STOVE;
        }
        return null;
    }
}
