package com.huidu.farmersdelight.api.block;

import com.huidu.farmersdelight.block.behavior.TallCropBlockBehavior;
import com.huidu.farmersdelight.block.behavior.TomatoVineBlockBehavior;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.block.Block;
import org.jetbrains.annotations.ApiStatus;

// Read-only crop gating queries for addons that hook custom crop logic: whether a block (or a block id)
// is one of FarmersDelight's managed crops, whether it is mature enough to harvest right now, and the
// harvest tool rules. All queries are pure registry lookups -- they never mutate the world or the crop --
// so they are safe to call from a region thread. Unknown / non-crop blocks resolve to sensible defaults
// (false / empty) rather than throwing.
@ApiStatus.NonExtendable
public final class FarmersDelightCrops {

    private FarmersDelightCrops() {
    }

    // A block is a FarmersDelight tall crop (e.g. rice / the two-half clones configured with the
    // tall-crop behavior) or the tomato-vine behavior family.
    public static boolean isCrop(Block block) {
        return isCropBlockId(blockKey(block));
    }

    public static boolean isCropBlockId(Key blockId) {
        return blockId != null
                && (TallCropBlockBehavior.getBehavior(blockId) != null
                || TomatoVineBlockBehavior.getBehavior(blockId) != null);
    }

    // Whether the block is currently ready to harvest: for tall crops the upper half must be at its max
    // age (a reset-on-harvest crop drops from the top), for tomato vines the age must reach the max.
    public static boolean isHarvestReady(Block block) {
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
        if (state == null || state.isEmpty()) {
            return false;
        }
        Key blockId = state.owner().value().id();
        TallCropBlockBehavior tallCrop = TallCropBlockBehavior.getBehavior(blockId);
        if (tallCrop != null) {
            return isTallCropHarvestReady(tallCrop, state);
        }
        TomatoVineBlockBehavior vine = TomatoVineBlockBehavior.getBehavior(blockId);
        return vine != null && vine.isHarvestReady(state);
    }

    // The current growth stage of a tall crop, or -1 when the block is not a tall crop.
    public static int tallCropAge(Block block) {
        TallCropBlockBehavior behavior = TallCropBlockBehavior.getBehavior(blockKey(block));
        if (behavior == null) {
            return -1;
        }
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
        return behavior.getAge(state);
    }

    // Whether the given block id was declared with reset-on-harvest (the top is harvested and the crop
    // regrows). False for non-crops.
    public static boolean resetsOnHarvest(String blockId) {
        TallCropBlockBehavior behavior = TallCropBlockBehavior.getBehavior(Key.of(blockId));
        return behavior != null && behavior.resetsOnHarvest();
    }

    private static boolean isTallCropHarvestReady(TallCropBlockBehavior behavior, ImmutableBlockState state) {
        if (behavior.isUpperHalf(state)) {
            return behavior.isUpperMature(state);
        }
        return false;
    }

    private static Key blockKey(Block block) {
        if (block == null) {
            return null;
        }
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
        return state == null || state.isEmpty() ? null : state.owner().value().id();
    }
}