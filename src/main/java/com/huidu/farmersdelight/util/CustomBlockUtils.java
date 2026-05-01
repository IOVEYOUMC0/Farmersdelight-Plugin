package com.huidu.farmersdelight.util;

import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.properties.Property;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

public final class CustomBlockUtils {

    private CustomBlockUtils() {
    }

    public static ImmutableBlockState getState(Block block) {
        if (block == null) {
            return null;
        }
        return CraftEngineBlocks.getCustomBlockState(block);
    }

    public static ImmutableBlockState getState(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        return getState(location.getBlock());
    }

    public static String getId(Block block) {
        return getId(getState(block));
    }

    public static String getId(ImmutableBlockState state) {
        if (state == null || state.isEmpty()) {
            return null;
        }
        try {
            return normalizeId(state.owner().value().id().toString());
        } catch (Exception ignored) {
            return null;
        }
    }

    public static boolean hasId(ImmutableBlockState state, String blockId) {
        return normalizeId(blockId).equals(getId(state));
    }

    public static boolean hasId(Block block, String blockId) {
        return normalizeId(blockId).equals(getId(block));
    }

    public static boolean hasId(Location location, String blockId) {
        return location != null && location.getWorld() != null && hasId(location.getBlock(), blockId);
    }

    public static boolean idContains(Block block, String fragment) {
        String blockId = getId(block);
        return blockId != null && blockId.contains(normalizeId(fragment));
    }

    public static String normalizeId(String rawId) {
        if (rawId == null) {
            return null;
        }

        String trimmed = rawId.trim();
        int slashIndex = trimmed.lastIndexOf('/');
        if (slashIndex >= 0) {
            trimmed = trimmed.substring(slashIndex + 1).trim();
        }

        if (trimmed.endsWith("]")) {
            int bracketIndex = trimmed.lastIndexOf('[');
            if (bracketIndex >= 0) {
                trimmed = trimmed.substring(bracketIndex + 1).trim();
            }
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }

        return trimmed;
    }

    public static BlockFace getFacing(Block block) {
        ImmutableBlockState state = getState(block);
        BlockFace stateFacing = getFacing(state);
        if (stateFacing != null) {
            return stateFacing;
        }

        if (block != null) {
            var blockData = block.getBlockData();
            if (blockData instanceof org.bukkit.block.data.Directional directional) {
                return directional.getFacing();
            }
        }

        return BlockFace.NORTH;
    }

    public static BlockFace getFacing(ImmutableBlockState state) {
        if (state == null || state.isEmpty()) {
            return null;
        }

        for (Property<?> property : state.getProperties()) {
            if ("facing".equalsIgnoreCase(property.name())) {
                Object value = state.get(property);
                if (value != null) {
                    return parseFacing(value.toString());
                }
            }
        }

        return null;
    }

    public static BlockFace parseFacing(String facingValue) {
        return switch (facingValue.toLowerCase()) {
            case "south" -> BlockFace.SOUTH;
            case "east" -> BlockFace.EAST;
            case "west" -> BlockFace.WEST;
            default -> BlockFace.NORTH;
        };
    }

    public static float getYRotation(BlockFace facing) {
        return switch (facing) {
            case SOUTH -> 0.0f;
            case WEST -> -90.0f;
            case EAST -> 90.0f;
            default -> 180.0f;
        };
    }
}
