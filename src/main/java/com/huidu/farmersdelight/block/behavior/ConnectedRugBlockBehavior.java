package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.util.BehaviorArgParser;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.compat.CraftEngineAdapter;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.EnumSet;
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
        public ConnectedRugBlockBehavior create(BlockDefinition block, net.momirealms.craftengine.core.plugin.config.ConfigSection section) {
            Map<String, Object> arguments = section != null ? section.values() : Map.of();
            String variantPropertyName = BehaviorArgParser.getString(arguments, "variant-property", null);
            Property<?> variantProperty = variantPropertyName == null ? null : block.getProperty(variantPropertyName);
            Set<String> connectedIds = parseStringList(arguments.get("connected-ids"));
            if (connectedIds.isEmpty()) {
                connectedIds.add(block.id().namespace() + ":" + block.id().value());
            }

            String path = section != null ? section.path() : Constants.BEHAVIOR_CONNECTED_RUG;
            if (variantPropertyName != null && variantProperty == null) {
                throw new net.momirealms.craftengine.core.plugin.config.KnownResourceException(
                        "resource.block.behavior.missing_property", path, variantPropertyName);
            }
            return new ConnectedRugBlockBehavior(block, variantProperty, connectedIds);
        }
    };

    @Override
    public void placeMultiState(Object thisBlock, Object[] args) {
        handleNeighborUpdate(args);
    }

    @Override
    public void neighborChanged(Object thisBlock, Object[] args) {
        handleNeighborUpdate(args);
    }

    @Override
    public void affectNeighborsAfterRemoval(Object thisBlock, Object[] args) {
        handleRemoval(args);
    }

    @Override
    public void spawnAfterBreak(Object thisBlock, Object[] args) {
        handleRemoval(args);
    }

    // A freshly placed rug recomputes its frayed edges immediately, and so does a neighbor change. Both
    // share the same entry point; the write is skipped when the computed variant already equals the current
    // one, so the state is stable and no redundant re-place happens.
    private void handleNeighborUpdate(Object[] args) {
        if (!variantEnabled || args.length < 3) {
            return;
        }
        World world = CraftEngineAdapter.toWorld(args[1]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
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

    private void writeVariant(World world, Block self, ImmutableBlockState state) {
        Set<BlockFace> connected = EnumSet.noneOf(BlockFace.class);
        for (BlockFace face : HORIZONTAL) {
            if (isConnected(CraftEngineBlocks.getCustomBlockState(self.getRelative(face)))) {
                connected.add(face);
            }
        }
        boolean north = connected.contains(BlockFace.NORTH);
        boolean south = connected.contains(BlockFace.SOUTH);
        boolean east = connected.contains(BlockFace.EAST);
        boolean west = connected.contains(BlockFace.WEST);

        // Each state names the edges whose fraying is removed; a connected neighbor removes that edge.
        // Branches are ordered from most-connected to least-connected so every combination is unique.
        String target;
        if (north && south && east && west) {
            target = "surrounded";
        } else if (north && south && east) {
            target = "no_north_south_east";
        } else if (north && south && west) {
            target = "no_north_south_west";
        } else if (north && east && west) {
            target = "no_east_west_north";
        } else if (south && east && west) {
            target = "no_east_west_south";
        } else if (north && south) {
            target = "no_north_south";
        } else if (east && west) {
            target = "no_east_west";
        } else if (north && east) {
            target = "no_north_east";
        } else if (north && west) {
            target = "no_north_west";
        } else if (south && east) {
            target = "no_south_east";
        } else if (south && west) {
            target = "no_south_west";
        } else if (north) {
            target = "no_north";
        } else if (south) {
            target = "no_south";
        } else if (east) {
            target = "no_east";
        } else if (west) {
            target = "no_west";
        } else {
            // No neighbors: fall back to the property default so removing a neighbor restores the plain
            // unfrayed appearance instead of leaving a stale one-sided cull behind.
            target = String.valueOf(variantProperty.defaultValue());
        }

        Object current = state.get(variantProperty);
        if (target.equals(String.valueOf(current))) {
            return;
        }
        CraftEngineBlocks.place(self.getLocation(), withPropertyValue(state, variantProperty, target), false);
    }

    private void refreshNeighbors(World world, Block center) {
        for (BlockFace face : HORIZONTAL) {
            Block neighbor = center.getRelative(face);
            ImmutableBlockState neighborState = CraftEngineBlocks.getCustomBlockState(neighbor);
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