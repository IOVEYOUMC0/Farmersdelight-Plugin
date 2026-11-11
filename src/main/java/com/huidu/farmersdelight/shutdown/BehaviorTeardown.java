package com.huidu.farmersdelight.shutdown;

import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CuttingBoardBlockBehavior;
import com.huidu.farmersdelight.block.behavior.MushroomColonyBehavior;
import com.huidu.farmersdelight.block.behavior.SkilletBlockBehavior;
import com.huidu.farmersdelight.block.behavior.StoveCookingBlockBehavior;
import com.huidu.farmersdelight.block.behavior.TallCropBlockBehavior;
import com.huidu.farmersdelight.block.behavior.WildRiceBlockBehavior;

import java.util.List;

/**
 * Ordered teardown of the behavior-side caches: every step drops the behavior's world references and pending
 * display state before the plugin class loader goes away. The order is behavior, not preference, so it is pinned
 * by {@code TeardownOrderTest}.
 */
public final class BehaviorTeardown {

    private BehaviorTeardown() {
    }

    /** The canonical order; adding a behavior appends here and to {@link #steps(boolean)}. */
    public static List<String> ids() {
        return List.of(
                "plugin.disable_step_cleanup_cooking_pot_block_entities",
                "plugin.disable_step_cleanup_cutting_board_block_entities",
                "plugin.disable_step_cleanup_stove_block_entities",
                "plugin.disable_step_cleanup_skillet_block_entities",
                "plugin.disable_step_cleanup_tall_crops",
                "plugin.disable_step_cleanup_mushroom_colonies",
                "plugin.disable_step_cleanup_wild_rice_blocks");
    }

    /** @param removeDisplayEntities whether the world is still alive enough to delete display entities */
    public static List<TeardownStep> steps(boolean removeDisplayEntities) {
        return List.of(
                new TeardownStep(ids().get(0),
                        () -> CookingPotBlockBehavior.cleanupAll(removeDisplayEntities)),
                new TeardownStep(ids().get(1),
                        () -> CuttingBoardBlockBehavior.cleanupAll(removeDisplayEntities)),
                new TeardownStep(ids().get(2), StoveCookingBlockBehavior::cleanupAll),
                new TeardownStep(ids().get(3), SkilletBlockBehavior::cleanupAll),
                new TeardownStep(ids().get(4), TallCropBlockBehavior::cleanupAll),
                new TeardownStep(ids().get(5), MushroomColonyBehavior::cleanupAll),
                new TeardownStep(ids().get(6), WildRiceBlockBehavior::cleanupAll));
    }
}
