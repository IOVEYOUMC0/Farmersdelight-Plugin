package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.util.BehaviorArgParser;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.compat.CraftEngineAdapter;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.plugin.config.KnownResourceException;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

// Generic "connected" block: recomputes a variant property that removes a frayed edge based on which
// same-family neighbors (connected-ids) are adjacent, using world-absolute directions so no furniture
// rotation mapping is involved. The variant property is optional: a block that only needs to participate
// as a connected neighbor (without drawing edges itself) simply omits variant-property.
public class ConnectedRugBlockBehavior extends RugBlockBehavior {

    private final boolean variantEnabled;
    private final Property<?> variantProperty;
    private final Set<String> connectedIds;

    private ConnectedRugBlockBehavior(
            BlockDefinition block,
            Property<?> variantProperty,
            Set<String> connectedIds) {
        super(block);
        this.variantEnabled = variantProperty != null;
        this.variantProperty = variantProperty;
        this.connectedIds = connectedIds;
    }

    public static final BlockBehaviorFactory<ConnectedRugBlockBehavior> FACTORY = new BlockBehaviorFactory<ConnectedRugBlockBehavior>() {
        @Override
        public ConnectedRugBlockBehavior create(BlockDefinition block, ConfigSection section) {
            Map<String, Object> arguments = section != null ? section.values() : Map.of();
            String variantPropertyName = BehaviorArgParser.getString(arguments, "variant-property", null);
            Property<?> variantProperty = variantPropertyName == null ? null : block.getProperty(variantPropertyName);
            Set<String> connectedIds = new HashSet<>(BehaviorArgParser.getStringList(arguments, "connected-ids"));
            if (connectedIds.isEmpty()) {
                connectedIds.add(block.id().namespace() + ":" + block.id().value());
            }

            String path = section != null ? section.path() : Constants.BEHAVIOR_CONNECTED_RUG;
            if (variantPropertyName != null && variantProperty == null) {
                throw new KnownResourceException(
                        "resource.block.behavior.missing_property", path, variantPropertyName);
            }
            return new ConnectedRugBlockBehavior(block, variantProperty, connectedIds);
        }
    };

    // A placement and a neighbour change hand over different native argument lists: the placement carries the
    // level first and the block position second, while the neighbour change carries the previous state first.
    // Reading the wrong index leaves the level null and skips the recompute without any error.
    @Override
    public void placeMultiState(Object thisBlock, Object[] args) {
        if (!variantEnabled || args == null || args.length < 2) {
            return;
        }
        recomputeSelf(args[0], args[1]);
    }

    @Override
    public void neighborChanged(Object thisBlock, Object[] args) {
        if (!variantEnabled || args == null || args.length < 3) {
            return;
        }
        recomputeSelf(args[1], args[2]);
    }

    @Override
    public void affectNeighborsAfterRemoval(Object thisBlock, Object[] args) {
        handleRemoval(args);
    }

    @Override
    public void spawnAfterBreak(Object thisBlock, Object[] args) {
        handleRemoval(args);
    }

    // A freshly placed rug recomputes its frayed edges immediately, and so does a neighbour change; the write
    // is skipped when the computed variant already equals the current one, so the state is stable and no
    // redundant re-place happens.
    private void recomputeSelf(Object levelArg, Object posArg) {
        World world = CraftEngineAdapter.toWorld(levelArg);
        BlockPos pos = CraftEngineAdapter.toBlockPos(posArg);
        if (world == null || pos == null) {
            return;
        }
        Block self = world.getBlockAt(pos.x(), pos.y(), pos.z());
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(self);
        if (state == null || state.isEmpty() || !isConnected(state)) {
            return;
        }
        writeVariant(world, self, state);
    }

    // When a block is removed, only the frayed edges of surviving horizontal neighbors need a refresh;
    // this behavior never removes partners, so there is nothing else to tear down.
    private void handleRemoval(Object[] args) {
        if (!variantEnabled || args == null || args.length < 3) {
            return;
        }
        World world = CraftEngineAdapter.toWorld(args[1]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
        if (world == null || pos == null) {
            return;
        }
        refreshNeighbors(world, world.getBlockAt(pos.x(), pos.y(), pos.z()));
    }

    // Each state names the edges whose fraying is removed: a connected neighbor removes that edge, so the
    // variant string is exactly "no_" + the connected directions. Indexed by a 4-bit mask (N|S|E|W) so every
    // combination maps to one value without a manual branching cascade.
    private static final String[] VARIANT_BY_MASK = {
            "none",                // no neighbors
            "no_north",            // N
            "no_south",            // S
            "no_north_south",      // N, S
            "no_east",             // E
            "no_north_east",       // N, E
            "no_south_east",       // S, E
            "no_north_south_east", // N, S, E
            "no_west",             // W
            "no_north_west",       // N, W
            "no_south_west",       // S, W
            "no_north_south_west", // N, S, W
            "no_east_west",        // E, W
            "no_east_west_north",  // N, E, W
            "no_east_west_south",  // S, E, W
            "surrounded"           // N, S, E, W
    };

    private void writeVariant(World world, Block self, ImmutableBlockState state) {
        Set<BlockFace> connected = EnumSet.noneOf(BlockFace.class);
        for (BlockFace face : HORIZONTAL) {
            Block neighbor = self.getRelative(face);
            // A neighbour this thread does not own counts as unconnected, so the frayed edge stays until the
            // neighbour's own next update corrects it; reading a foreign chunk here would cross the region.
            if (!Bukkit.isOwnedByCurrentRegion(neighbor)) {
                continue;
            }
            // A neighbour in a chunk that is not loaded counts as unconnected: reading its CE state would load
            // the chunk, and the variant rewrite below would cross into the owning region.
            if (isConnected(CustomBlockUtils.getStateIfResident(neighbor))) {
                connected.add(face);
            }
        }
        int mask = 0;
        if (connected.contains(BlockFace.NORTH)) mask |= 1;
        if (connected.contains(BlockFace.SOUTH)) mask |= 2;
        if (connected.contains(BlockFace.EAST)) mask |= 4;
        if (connected.contains(BlockFace.WEST)) mask |= 8;

        // With no neighbors the mask is 0, which restores the plain unfrayed appearance instead of leaving a
        // stale one-sided cull behind.
        String target = VARIANT_BY_MASK[mask];
        Object current = state.get(variantProperty);
        if (target.equals(String.valueOf(current))) {
            return;
        }
        CraftEngineBlocks.place(self.getLocation(), withPropertyValue(state, variantProperty, target), false);
    }

    private void refreshNeighbors(World world, Block center) {
        for (BlockFace face : HORIZONTAL) {
            Block neighbor = center.getRelative(face);
            // A loaded neighbour can still belong to another region, and both the state lookup below and the
            // variant rewrite it feeds would then run on the wrong thread. Skip it: the neighbour's own next
            // update retries the refresh.
            if (!Bukkit.isOwnedByCurrentRegion(neighbor)) {
                continue;
            }
            ImmutableBlockState neighborState = CustomBlockUtils.getStateIfResident(neighbor);
            if (neighborState == null || neighborState.isEmpty() || !isConnected(neighborState)) {
                continue;
            }
            if (neighborState.get(variantProperty) != null) {
                writeVariant(world, neighbor, neighborState);
            }
        }
    }

    private boolean isConnected(ImmutableBlockState state) {
        return matchesAnyId(state, connectedIds);
    }
}
