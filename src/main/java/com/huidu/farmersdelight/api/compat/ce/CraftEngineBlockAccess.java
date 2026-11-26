package com.huidu.farmersdelight.api.compat.ce;

import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.CEWorld;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.jetbrains.annotations.ApiStatus;

/**
 * CraftEngine-pinned block/world access for advanced addon behaviour code.
 *
 * <p>This package is deliberately separate from the neutral API: it exposes CraftEngine types and
 * follows the deployed CraftEngine version. Addons that only need content ids, recipes, events or
 * station/crop queries should not need it.</p>
 */
@ApiStatus.Experimental
public final class CraftEngineBlockAccess {

    private CraftEngineBlockAccess() {
    }

    public static CEWorld getCEWorld(World world) {
        return com.huidu.farmersdelight.api.block.CraftEngineBlockAccess.getCEWorld(world);
    }

    public static World getBukkitWorld(BlockEntity blockEntity) {
        return com.huidu.farmersdelight.api.block.CraftEngineBlockAccess.getBukkitWorld(blockEntity);
    }

    public static World toWorld(Object levelHandle) {
        return com.huidu.farmersdelight.api.block.CraftEngineBlockAccess.toWorld(levelHandle);
    }

    public static BlockPos toBlockPos(Object posHandle) {
        return com.huidu.farmersdelight.api.block.CraftEngineBlockAccess.toBlockPos(posHandle);
    }

    public static void markDirty(BlockEntity blockEntity) {
        com.huidu.farmersdelight.api.block.CraftEngineBlockAccess.markDirty(blockEntity);
    }

    public static String blockId(ImmutableBlockState state) {
        return com.huidu.farmersdelight.api.block.CraftEngineBlockAccess.blockId(state);
    }

    public static BlockFace facing(ImmutableBlockState state) {
        return com.huidu.farmersdelight.api.block.CraftEngineBlockAccess.facing(state);
    }

    public static String property(ImmutableBlockState state, String propertyName) {
        return com.huidu.farmersdelight.api.block.CraftEngineBlockAccess.property(state, propertyName);
    }

    public static BlockEntityController blockEntityController(World world, int x, int y, int z) {
        return com.huidu.farmersdelight.api.block.CraftEngineBlockAccess.blockEntityController(world, x, y, z);
    }
}
