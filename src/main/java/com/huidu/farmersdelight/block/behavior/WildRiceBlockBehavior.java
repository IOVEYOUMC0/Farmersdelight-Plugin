package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.util.BehaviorArgParser;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.RiceCropRules;
import com.huidu.farmersdelight.util.SoilRuleSupport;
import com.huidu.farmersdelight.util.SoilRuleSupport.SoilRules;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehavior;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.block.property.type.DoubleBlockHalf;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class WildRiceBlockBehavior extends FarmersDelightBlockBehavior {

    @Override
    public boolean isPathFindable(Object thisBlock, Object[] args) {
        return false;
    }

    private static final Map<Key, WildRiceBlockBehavior> BEHAVIORS = new ConcurrentHashMap<>();
    private static final SoilRules FALLBACK_SOIL_RULES = new SoilRules(
            Set.of(
                    Material.DIRT,
                    Material.GRASS_BLOCK,
                    Material.COARSE_DIRT,
                    Material.ROOTED_DIRT,
                    Material.PODZOL,
                    Material.MYCELIUM,
                    Material.MUD,
                    Material.SAND,
                    Material.RED_SAND
            ),
            Set.of(),
            Set.of(),
            List.<BlockData>of(),
            Set.of()
    );

    public static final String HALF_PROPERTY = "half";

    // Resolved once at construction from the block definition this behavior belongs to, so the handle can
    // never go stale: a /ce reload rebuilds the definition and its Property instances together with this
    // behavior. Final, so it is safely published to the region threads that read it.
    private final Property<?> halfProperty;
    private final Object halfLowerValue;
    private final Object halfUpperValue;
    private final boolean requiresWater;
    private final SoilRules soilRules;

    private WildRiceBlockBehavior(
            BlockDefinition block,
            Property<?> halfProperty,
            Object halfLowerValue,
            Object halfUpperValue,
            boolean requiresWater,
            SoilRules soilRules
    ) {
        super(block);
        this.halfProperty = halfProperty;
        this.halfLowerValue = halfLowerValue;
        this.halfUpperValue = halfUpperValue;
        this.requiresWater = requiresWater;
        this.soilRules = soilRules;
    }

    public static final BlockBehaviorFactory<WildRiceBlockBehavior> FACTORY = new BlockBehaviorFactory<WildRiceBlockBehavior>() {
        @Override
        public WildRiceBlockBehavior create(BlockDefinition block, net.momirealms.craftengine.core.plugin.config.ConfigSection section) {
            Map<String, Object> arguments = section != null ? section.values() : Map.of();
            // The half state is not optional: without it every state reads as the lower half, so the
            // upper half of a placed plant fails its own survival check (it looks for soil under it and
            // finds the lower plant) and is removed. A block that declares this behavior without a
            // double_block_half 'half' property aborts its own load here, with the config node and the
            // property name in the message, instead of destroying placed plants at runtime.
            String path = section != null ? section.path() : Constants.BEHAVIOR_WILD_RICE;
            Property<DoubleBlockHalf> halfProperty =
                    BlockBehaviorFactory.getProperty(path, block, HALF_PROPERTY, DoubleBlockHalf.class);
            Object lowerHalfValue = inferHalfValue(halfProperty, "lower");
            Object upperHalfValue = inferHalfValue(halfProperty, "upper");
            boolean requiresWater = BehaviorArgParser.getBooleanStrict(arguments, "requires-water", true);
            SoilRules soilRules = SoilRuleSupport.parseSoilRules(arguments);
            WildRiceBlockBehavior behavior = new WildRiceBlockBehavior(
                    block,
                    halfProperty,
                    lowerHalfValue,
                    upperHalfValue,
                    requiresWater,
                    soilRules
            );
            BEHAVIORS.put(block.id(), behavior);
            return behavior;
        }
    };

    public static WildRiceBlockBehavior getBehavior(Key blockId) {
        if (blockId == null) {
            return null;
        }
        return BEHAVIORS.get(blockId);
    }

    public static void cleanupAll() {
        BEHAVIORS.clear();
    }

    public static WildRiceBlockBehavior getBehavior(ImmutableBlockState state) {
        if (state == null || state.isEmpty()) {
            return null;
        }
        return getBehavior(state.owner().value().id());
    }

    public boolean canPlantAt(Block waterBlock) {
        if (waterBlock == null) {
            return false;
        }
        if (requiresWater && !RiceCropRules.isSourceWater(waterBlock)) {
            return false;
        }
        Block upperBlock = waterBlock.getRelative(BlockFace.UP);
        if (!upperBlock.getType().isAir()) {
            return false;
        }
        return isValidSoil(waterBlock.getRelative(BlockFace.DOWN));
    }

    public boolean canStay(Block block, ImmutableBlockState state) {
        if (block == null || state == null || state.isEmpty()) {
            return false;
        }
        Object half = getHalf(state);
        if (half == null) {
            // A state whose half cannot be read is left in place. This check drives removal, so an
            // unreadable state must never be classified as a lower half and then fail the soil test.
            return true;
        }
        if (matchesHalfValue(half, halfUpperValue)) {
            return matchesWildRice(block.getRelative(BlockFace.DOWN));
        }
        return isValidSoil(block.getRelative(BlockFace.DOWN));
    }

    public boolean isValidSoil(Block block) {
        SoilRules rules = soilRules != null && soilRules.isConfigured() ? soilRules : FALLBACK_SOIL_RULES;
        return SoilRuleSupport.matches(block, rules);
    }

    public boolean isUpperHalf(ImmutableBlockState state) {
        return matchesHalfValue(getHalf(state), halfUpperValue);
    }

    public boolean isLowerHalf(ImmutableBlockState state) {
        return matchesHalfValue(getHalf(state), halfLowerValue);
    }

    public Object lowerHalfValue() {
        return halfLowerValue;
    }

    public Object upperHalfValue() {
        return halfUpperValue;
    }

    private boolean matchesWildRice(Block block) {
        if (block == null) {
            return false;
        }
        ImmutableBlockState lowerState = net.momirealms.craftengine.bukkit.api.CraftEngineBlocks.getCustomBlockState(block);
        return lowerState != null
                && !lowerState.isEmpty()
                && lowerState.owner().value().id().equals(this.blockDefinition.id())
                && isLowerHalf(lowerState);
    }

    private Object getHalf(ImmutableBlockState state) {
        if (state == null || state.isEmpty()) {
            return null;
        }
        return state.getNullable(halfProperty);
    }

    private boolean matchesHalfValue(Object actual, Object expected) {
        if (actual == expected) {
            return true;
        }
        if (actual == null || expected == null) {
            return false;
        }
        if (actual.equals(expected)) {
            return true;
        }
        return String.valueOf(actual).trim().equalsIgnoreCase(String.valueOf(expected).trim());
    }

    private static Object inferHalfValue(Property<?> halfProperty, String fallback) {
        for (Object candidate : halfProperty.possibleValues()) {
            if (String.valueOf(candidate).trim().equalsIgnoreCase(fallback)) {
                return candidate;
            }
        }
        return fallback;
    }

}

