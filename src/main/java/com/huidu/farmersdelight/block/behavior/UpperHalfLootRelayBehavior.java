package com.huidu.farmersdelight.block.behavior;

import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehavior;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.property.Property;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.Map;
import java.util.Objects;

public class UpperHalfLootRelayBehavior extends BlockBehavior {

    @Override
    public boolean isPathFindable(Object thisBlock, Object[] args) {
        return false;
    }

    @Override
    public void fallOn(Object thisBlock, Object[] args) {
    }

    @Override
    public void updateEntityMovementAfterFallOn(Object thisBlock, Object[] args) {
    }
    private final BlockFace lowerHalfDirection;
    private final boolean requireMatchingLowerHalf;
    private final boolean requireMatchingBlock;
    private final String halfPropertyName;
    private final String lowerHalfValue;
    private final String upperHalfValue;

    private UpperHalfLootRelayBehavior(
            BlockDefinition block,
            BlockFace lowerHalfDirection,
            boolean requireMatchingLowerHalf,
            boolean requireMatchingBlock,
            String halfPropertyName,
            String lowerHalfValue,
            String upperHalfValue
    ) {
        super(block);
        this.lowerHalfDirection = lowerHalfDirection;
        this.requireMatchingLowerHalf = requireMatchingLowerHalf;
        this.requireMatchingBlock = requireMatchingBlock;
        this.halfPropertyName = halfPropertyName;
        this.lowerHalfValue = lowerHalfValue;
        this.upperHalfValue = upperHalfValue;
    }

    public static final BlockBehaviorFactory<UpperHalfLootRelayBehavior> FACTORY =
            new BlockBehaviorFactory<UpperHalfLootRelayBehavior>() {
                @Override
                public UpperHalfLootRelayBehavior create(BlockDefinition block, net.momirealms.craftengine.core.plugin.config.ConfigSection section) {
                    Map<String, Object> arguments = section != null ? section.values() : Map.of();
                    String lowerHalfDirectionName = getString(arguments, "lower-half-direction", "DOWN");
                    boolean requireMatchingLowerHalf = getBoolean(arguments, "require-matching-lower-half", true);
                    boolean requireMatchingBlock = getBoolean(arguments, "require-matching-block", true);
                    String halfPropertyName = getString(arguments, "half-property", "half");
                    String lowerHalfValue = getString(arguments, "half-lower-value", "lower");
                    String upperHalfValue = getString(arguments, "half-upper-value", "upper");
                    BlockFace lowerHalfDirection = parseDirection(lowerHalfDirectionName);
                    return new UpperHalfLootRelayBehavior(
                            block,
                            lowerHalfDirection,
                            requireMatchingLowerHalf,
                            requireMatchingBlock,
                            halfPropertyName,
                            lowerHalfValue,
                            upperHalfValue
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
        return matchesHalfValue(getPropertyString(state, halfPropertyName), upperHalfValue);
    }

    public BlockFace getLowerHalfDirection() {
        return lowerHalfDirection;
    }

    public BlockFace getUpperHalfDirection() {
        return lowerHalfDirection.getOppositeFace();
    }

    private boolean matchesLowerHalf(ImmutableBlockState lowerState) {
        if (lowerState == null || lowerState.isEmpty()) {
            return false;
        }
        if (requireMatchingBlock && !Objects.equals(lowerState.owner().value().id(), this.blockDefinition.id())) {
            return false;
        }
        return matchesHalfValue(getPropertyString(lowerState, halfPropertyName), lowerHalfValue);
    }

    private boolean matchesHalfValue(String actual, String expected) {
        if (actual == null || expected == null) {
            return false;
        }
        return normalizeHalfValue(actual).equals(normalizeHalfValue(expected));
    }

    private String normalizeHalfValue(String value) {
        return value.trim().toLowerCase();
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

