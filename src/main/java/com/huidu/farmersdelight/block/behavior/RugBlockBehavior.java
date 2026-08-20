package com.huidu.farmersdelight.block.behavior;

import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.util.Direction;
import org.bukkit.block.BlockFace;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Shared base for the two rug behaviors that replaced the old legacy furniture: ConnectedRugBlockBehavior
// (draws frayed edges from world-absolute adjacency to same-family ids) and DoubleBlockRugBlockBehavior
// (a 2-cell mat that spawns head + foot along the player facing and tears its partner down on removal).
// The base only holds path-findability and the small set of text/value helpers both subclasses reuse.
public abstract class RugBlockBehavior extends FarmersDelightBlockBehavior {

    protected static final BlockFace[] HORIZONTAL = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    public RugBlockBehavior(BlockDefinition blockDefinition) {
        super(blockDefinition);
    }

    @Override
    public boolean isPathFindable(Object thisBlock, Object[] args) {
        return false;
    }

    // -- shared helpers ------------------------------------------------------

    protected static Set<String> parseStringList(Object raw) {
        Set<String> ids = new HashSet<>();
        if (raw instanceof List<?> list) {
            for (Object entry : list) {
                if (entry != null) {
                    ids.add(String.valueOf(entry));
                }
            }
        }
        return ids;
    }

    protected static boolean matchesAnyId(ImmutableBlockState state, Set<String> ids) {
        if (state == null || state.isEmpty()) {
            return false;
        }
        String id = stateId(state);
        return id != null && ids.contains(id);
    }

    protected static String stateId(ImmutableBlockState state) {
        return state.owner().keyOptional()
                .map(key -> key.location().toString())
                .orElse(null);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    protected static ImmutableBlockState withPropertyValue(ImmutableBlockState state, Property property, String valueName) {
        Comparable<?> value = property.valueByName(valueName);
        return value == null ? state : ImmutableBlockState.with(state, property, value);
    }

    protected static Direction toDirection(BlockFace face) {
        return switch (face) {
            case SOUTH -> Direction.SOUTH;
            case EAST -> Direction.EAST;
            case WEST -> Direction.WEST;
            default -> Direction.NORTH;
        };
    }

    protected static BlockFace fromDirection(Direction direction) {
        return switch (direction) {
            case SOUTH -> BlockFace.SOUTH;
            case EAST -> BlockFace.EAST;
            case WEST -> BlockFace.WEST;
            default -> BlockFace.NORTH;
        };
    }
}