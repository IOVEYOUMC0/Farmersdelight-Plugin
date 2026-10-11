package com.huidu.farmersdelight.api.block;

import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.compat.CraftEngineAdapter;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.CEWorld;
import org.bukkit.World;
import org.bukkit.block.BlockFace;

/**
 * Shared, null-tolerant CE block and world lookups for addon behavior code.
 * World resolution handles registry readiness and CE return-type compatibility.
 * Callers must own a block's region before accessing its live state or controller.
 */
public final class CraftEngineBlockAccess {

    private CraftEngineBlockAccess() {
    }

    /** The CE storage world, or null when its registry or saved data cannot be resolved safely. */
    public static CEWorld getCEWorld(World world) {
        return CustomBlockUtils.getCEWorld(world);
    }

    /** The Bukkit world owning this block entity, or null. */
    public static World getBukkitWorld(BlockEntity blockEntity) {
        return CustomBlockUtils.getBukkitWorld(blockEntity);
    }

    /** Converts a CraftEngine/NMS level handle to its Bukkit world, or null when it is not one. */
    public static World toWorld(Object levelHandle) {
        return CraftEngineAdapter.toWorld(levelHandle);
    }

    /** Converts a CraftEngine/NMS block-position handle to a CE BlockPos, or null. */
    public static BlockPos toBlockPos(Object posHandle) {
        return CraftEngineAdapter.toBlockPos(posHandle);
    }

    /**
     * Flags the block entity's chunk as unsaved. CraftEngine only serialises chunks marked dirty, so
     * every state change that must survive a restart has to be followed by this call.
     */
    public static void markDirty(BlockEntity blockEntity) {
        CustomBlockUtils.markBlockEntityDirty(blockEntity);
    }

    /** The custom block id of a state ("namespace:id"), or null when the state is not a custom block. */
    public static String blockId(ImmutableBlockState state) {
        return CustomBlockUtils.getId(state);
    }

    /** The horizontal facing of a state, defaulting to NORTH when it has no facing property. */
    public static BlockFace facing(ImmutableBlockState state) {
        return CustomBlockUtils.getFacing(state);
    }

    /** A state property as a string, or null when the state has no such property. */
    public static String property(ImmutableBlockState state, String propertyName) {
        return CustomBlockUtils.getPropertyString(state, propertyName);
    }

    /** A controller in an already loaded chunk, or null. The caller must own the position's region. */
    public static BlockEntityController blockEntityController(World world, int x, int y, int z) {
        CEWorld ceWorld = getCEWorld(world);
        if (ceWorld == null) {
            return null;
        }
        BlockEntity blockEntity = ceWorld.getBlockEntityAtIfLoaded(new BlockPos(x, y, z));
        return blockEntity == null ? null : blockEntity.controller;
    }
}
