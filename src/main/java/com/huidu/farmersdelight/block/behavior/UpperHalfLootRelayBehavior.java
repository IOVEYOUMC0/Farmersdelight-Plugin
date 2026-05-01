package com.huidu.farmersdelight.block.behavior;

import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.CustomBlock;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehavior;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.properties.Property;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.Map;
import java.util.Objects;

public class UpperHalfLootRelayBehavior extends BlockBehavior {
    private final BlockFace lowerHalfDirection;
    private final boolean requireMatchingLowerHalf;

    private UpperHalfLootRelayBehavior(
            CustomBlock block,
            BlockFace lowerHalfDirection,
            boolean requireMatchingLowerHalf
    ) {
        super(block);
        this.lowerHalfDirection = lowerHalfDirection;
        this.requireMatchingLowerHalf = requireMatchingLowerHalf;
    }

    public static final BlockBehaviorFactory<UpperHalfLootRelayBehavior> FACTORY =
            new BlockBehaviorFactory<UpperHalfLootRelayBehavior>() {
                @Override
                public UpperHalfLootRelayBehavior create(CustomBlock block, Map<String, Object> arguments) {
                    String lowerHalfDirectionName = getString(arguments, "lower-half-direction", "DOWN");
                    boolean requireMatchingLowerHalf = getBoolean(arguments, "require-matching-lower-half", true);
                    BlockFace lowerHalfDirection = parseDirection(lowerHalfDirectionName);
                    return new UpperHalfLootRelayBehavior(
                            block,
                            lowerHalfDirection,
                            requireMatchingLowerHalf
                    );
                }
            };

    public boolean shouldRelayUpperHalfLoot(ImmutableBlockState state, Block brokenBlock) {
        if (!isUpperHalf(state)) {
            return false;
        }
        if (!requireMatchingLowerHalf) {
            return true;
        }
        Block lowerBlock = brokenBlock.getRelative(lowerHalfDirection);
        ImmutableBlockState lowerState = CraftEngineBlocks.getCustomBlockState(lowerBlock);
        return matchesLowerHalf(lowerState);
    }

    public boolean isUpperHalf(ImmutableBlockState state) {
        String halfValue = getPropertyString(state, "half");
        return "upper".equalsIgnoreCase(halfValue);
    }

    private boolean matchesLowerHalf(ImmutableBlockState lowerState) {
        if (lowerState == null || lowerState.isEmpty()) {
            return false;
        }
        if (!Objects.equals(lowerState.owner().value().id(), this.customBlock.id())) {
            return false;
        }
        return !isUpperHalf(lowerState);
    }

    private String getPropertyString(ImmutableBlockState state, String propertyName) {
        if (state == null || state.isEmpty()) {
            return null;
        }
        try {
            Property<?> property = state.owner().value().getProperty(propertyName);
            if (property == null) {
                return null;
            }
            Object value = state.get(property);
            if (value == null) {
                return null;
            }
            return value.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static BlockFace parseDirection(String directionName) {
        try {
            return BlockFace.valueOf(directionName.toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return BlockFace.DOWN;
        }
    }

    private static String getString(Map<String, Object> arguments, String key, String fallback) {
        Object value = arguments.get(key);
        if (value instanceof String stringValue && !stringValue.isEmpty()) {
            return stringValue;
        }
        return fallback;
    }

    private static boolean getBoolean(Map<String, Object> arguments, String key, boolean fallback) {
        Object value = arguments.get(key);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return fallback;
    }
}
