package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.listener.RopeBlockListener;
import com.huidu.farmersdelight.util.compat.CraftEngineAdapter;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockSupport;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Gate;

import java.util.List;

/** Supplements CE gate behavior with native wall connections. */
public final class RopeGateWallBehavior extends FarmersDelightBlockBehavior {
    private static final List<BlockFace> SIDES = List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST);
    public static final BlockBehaviorFactory<RopeGateWallBehavior> FACTORY = (block, section) ->
            new RopeGateWallBehavior(block, BlockBehaviorFactory.getOptionalProperty(block, "facing", Direction.class));
    private final Property<Direction> facing;

    private RopeGateWallBehavior(BlockDefinition block, Property<Direction> facing) {
        super(block);
        if (facing == null) throw new IllegalArgumentException("Rope gate requires a horizontal facing property");
        this.facing = facing;
    }

    @Override
    public boolean isPathFindable(Object thisBlock, Object[] args) {
        return false;
    }

    @Override
    public void onPlace(Object thisBlock, Object[] args) {
        refreshConnections(CraftEngineAdapter.toWorld(args[1]), CraftEngineAdapter.toBlockPos(args[2]),
                BlockStateUtils.getOptionalCustomBlockState(args[0]).orElse(null));
    }

    @Override
    public void neighborChanged(Object thisBlock, Object[] args) {
        onPlace(thisBlock, args);
    }

    @Override
    public Object updateShape(Object thisBlock, Object[] args) {
        refreshConnections(CraftEngineAdapter.toWorld(args[1]), CraftEngineAdapter.toBlockPos(args[3]),
                BlockStateUtils.getOptionalCustomBlockState(args[0]).orElse(null));
        return args[0];
    }

    @Override
    public void affectNeighborsAfterRemoval(Object thisBlock, Object[] args) {
        World world = CraftEngineAdapter.toWorld(args[1]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
        if (world == null || pos == null) return;
        refreshConnections(world, pos, CraftEngineBlocks.getCustomBlockState(world.getBlockAt(pos.x(), pos.y(), pos.z())));
    }

    public void refreshConnections(World world, BlockPos pos, ImmutableBlockState state) {
        if (world == null || pos == null) return;
        BlockFace gateFacing = state != null && state.owner().value().id().equals(block().id())
                ? BlockFace.valueOf(state.get(facing).name()) : null;
        BlockData replacement = gateFacing == null ? world.getBlockAt(pos.x(), pos.y(), pos.z()).getBlockData() : null;
        for (BlockFace side : SIDES) {
            boolean connected = gateFacing != null ? connects(gateFacing, side) : connects(replacement, side);
            RopeBlockListener.queueWallConnection(world,
                    new BlockPos(pos.x() + side.getModX(), pos.y(), pos.z() + side.getModZ()),
                    side.getOppositeFace(), connected);
        }
    }

    static boolean connects(BlockFace facing, BlockFace side) {
        return facing.getModX() != 0 ? side.getModZ() != 0 : side.getModX() != 0;
    }

    private static boolean connects(BlockData data, BlockFace side) {
        if (data instanceof Gate gate) return connects(gate.getFacing(), side);
        return Tag.WALLS.isTagged(data.getMaterial()) || data.isFaceSturdy(side, BlockSupport.FULL);
    }
}
