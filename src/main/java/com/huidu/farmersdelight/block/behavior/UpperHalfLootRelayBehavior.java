package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.util.BehaviorArgParser;
import com.huidu.farmersdelight.util.Constants;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.plugin.config.KnownResourceException;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.Map;
import java.util.Objects;

public class UpperHalfLootRelayBehavior extends FarmersDelightBlockBehavior {

    @Override
    public boolean isPathFindable(Object thisBlock, Object[] args) {
        return false;
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
                    String lowerHalfDirectionName = BehaviorArgParser.getStringStrict(arguments, "lower-half-direction", "DOWN");
                    boolean requireMatchingLowerHalf = BehaviorArgParser.getBooleanStrict(arguments, "require-matching-lower-half", true);
                    boolean requireMatchingBlock = BehaviorArgParser.getBooleanStrict(arguments, "require-matching-block", true);
                    String halfPropertyName = BehaviorArgParser.getStringStrict(arguments, "half-property", "half");
                    String lowerHalfValue = BehaviorArgParser.getStringStrict(arguments, "half-lower-value", "lower");
                    String upperHalfValue = BehaviorArgParser.getStringStrict(arguments, "half-upper-value", "upper");
                    // Telling the two halves apart is the whole of this behavior: without the property every
                    // state reads as "not the upper half", the relay never fires and the block silently drops
                    // its own loot instead of the lower half's. Abort the block's load here with the config
                    // node and the property name rather than let it look correct and do nothing. The property
                    // is looked up by name only, with no value class, because the half values are compared as
                    // text and any property type whose values spell out the two halves is accepted.
                    String path = section != null ? section.path() : Constants.BEHAVIOR_UPPER_HALF_LOOT_RELAY;
                    if (block.getProperty(halfPropertyName) == null) {
                        throw new KnownResourceException(
                                "resource.block.behavior.missing_property", path, halfPropertyName);
                    }
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
        return value.trim().toLowerCase(java.util.Locale.ROOT);
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
            return BlockFace.valueOf(directionName.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return BlockFace.DOWN;
        }
    }

}

