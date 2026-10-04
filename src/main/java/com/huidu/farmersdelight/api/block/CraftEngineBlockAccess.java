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
 * The CraftEngine block plumbing FarmersDelight uses internally, published so addons do not each
 * rewrite it.
 *
 *
 * None of this is exotic — resolving a CE world, turning a block entity back into a Bukkit world,
 * reading a state's id or facing, marking a chunk dirty — but every copy of it in this plugin family
 * had drifted, and none of the copies carried the guards FD added after a production crash (see
 * getCEWorld). One owner, one set of guards.
 *
 *
 * All methods are null-tolerant and return null / false rather than throwing: the callers are
 * block behaviours and chunk listeners, where an exception aborts something much larger.
 */
public final class CraftEngineBlockAccess {

    private CraftEngineBlockAccess() {
    }

    /**
     * The CE storage world for a Bukkit world, or null when CraftEngine is not ready or the world's
     * data cannot be loaded.
     *
     *
     * Do not call BukkitWorldManager.instance().getWorld(uuid).ceWorld() yourself. Before
     * CraftEngine binds its blocks, resolving a world deserializes saved chunk data against an unbound
     * registry; a block left over from an uninstalled pack then throws, and because chunk-load handlers
     * are a common caller it throws for every chunk. This applies the readiness gate, tolerates both CE
     * return shapes, and logs the failure once instead of propagating it.
     */
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

    /**
     * The block-entity controller at a world position, or null when it cannot be read.
     *
     *
     * Null when the world is unknown to CraftEngine (see {@link #getCEWorld(World)}), when the chunk is not
     * loaded, or when the position holds no block entity. Only an already loaded chunk is consulted, so this
     * never loads one and never returns data a region would have to be scheduled for.
     *
     * @return the controller, or null when there is none to read
     */
    public static BlockEntityController blockEntityController(World world, int x, int y, int z) {
        CEWorld ceWorld = getCEWorld(world);
        if (ceWorld == null) {
            return null;
        }
        BlockEntity blockEntity = ceWorld.getBlockEntityAtIfLoaded(new BlockPos(x, y, z));
        return blockEntity == null ? null : blockEntity.controller;
    }
}
