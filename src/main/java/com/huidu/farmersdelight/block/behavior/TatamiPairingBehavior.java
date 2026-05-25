package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.util.CraftEngineAdapter;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehavior;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

public class TatamiPairingBehavior extends BlockBehavior {

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
    private static volatile String tatamiBlockId = "farmersdelight:tatami";
    private static volatile String facingPropertyName = "facing";
    private static volatile String pairedPropertyName = "paired";

    private final Property<?> facingProperty;
    private final Property<Boolean> pairedProperty;
    private final boolean pairWhileSneaking;

    private TatamiPairingBehavior(BlockDefinition block, Property<?> facingProperty, Property<Boolean> pairedProperty, boolean pairWhileSneaking) {
        super(block);
        this.facingProperty = facingProperty;
        this.pairedProperty = pairedProperty;
        this.pairWhileSneaking = pairWhileSneaking;
    }

    @SuppressWarnings("unchecked")
    public static final BlockBehaviorFactory<TatamiPairingBehavior> FACTORY = new BlockBehaviorFactory<TatamiPairingBehavior>() {
        @Override
        public TatamiPairingBehavior create(BlockDefinition block, net.momirealms.craftengine.core.plugin.config.ConfigSection section) {
            Map<String, Object> arguments = section != null ? section.values() : Map.of();
            tatamiBlockId = getString(arguments, "block-id", tatamiBlockId);
            facingPropertyName = getString(arguments, "facing-property", facingPropertyName);
            pairedPropertyName = getString(arguments, "paired-property", pairedPropertyName);
            boolean pairWhileSneaking = getBoolean(arguments, "pair-while-sneaking", false);

            Property<?> facingProperty = block.getProperty(facingPropertyName);
            Property<Boolean> pairedProperty = (Property<Boolean>) block.getProperty(pairedPropertyName);

            return new TatamiPairingBehavior(block, facingProperty, pairedProperty, pairWhileSneaking);
        }
    };

    @Override
    public void placeMultiState(Object thisBlock, Object[] args) {
        if (args.length >= 5) {
            World world = CraftEngineAdapter.toWorld(args[0]);
            if (!(args[1] instanceof BlockPos pos) || world == null) {
                
                return;
            }

            Player player = args[3] == null
                    ? null
                    : Bukkit.getPlayer(((net.momirealms.craftengine.core.entity.player.Player) args[3]).uuid());
            Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
            ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
            if (state == null || state.isEmpty() || player == null || (player.isSneaking() && !pairWhileSneaking)) {
                
                return;
            }

            if (pairWithNeighbor(world, pos, state)) {
                // Re-place the just-placed block with paired=true so both halves stay visually synced.
                CraftEngineBlocks.place(block.getLocation(), state.with(pairedProperty, true), false);
            }
        }
        
    }

    private boolean pairWithNeighbor(World world, BlockPos pos, ImmutableBlockState state) {
        BlockFace facing = getFacing(state);
        BlockPos neighborPos = new BlockPos(
                pos.x() + facing.getModX(),
                pos.y() + facing.getModY(),
                pos.z() + facing.getModZ()
        );
        Block neighborBlock = world.getBlockAt(neighborPos.x(), neighborPos.y(), neighborPos.z());
        ImmutableBlockState neighborState = CraftEngineBlocks.getCustomBlockState(neighborBlock);
        if (neighborState == null || neighborState.isEmpty() || !isSameTatami(state, neighborState)) {
            return false;
        }

        if (pairedProperty == null || facingProperty == null) {
            return false;
        }

        Boolean neighborPaired = neighborState.get(pairedProperty);
        if (Boolean.TRUE.equals(neighborPaired)) {
            return false;
        }

        CraftEngineBlocks.place(
                neighborBlock.getLocation(),
                withFacingAndPair(neighborState, facing.getOppositeFace(), true),
                false
        );
        return true;
    }

    @SuppressWarnings("unchecked")
    private ImmutableBlockState withFacingAndPair(ImmutableBlockState state, BlockFace facing, boolean paired) {
        ImmutableBlockState result = state;
        if (facingProperty != null) {
            Property<String> stringProperty = (Property<String>) facingProperty;
            result = result.with(stringProperty, facing.name().toLowerCase());
        }
        if (pairedProperty != null) {
            result = result.with(pairedProperty, paired);
        }
        return result;
    }

    @Override
    public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) {
        return InteractionResult.PASS;
    }

    public static void refreshAdjacentTatami(Location brokenLocation) {
        if (brokenLocation == null || brokenLocation.getWorld() == null) {
            return;
        }

        World world = brokenLocation.getWorld();
        Block centerBlock = world.getBlockAt(brokenLocation);
        for (BlockFace face : BlockFace.values()) {
            if (face == BlockFace.SELF) {
                continue;
            }
            refreshTatamiState(centerBlock.getRelative(face));
        }
    }

    private BlockFace getFacing(ImmutableBlockState state) {
        return getFacingFromState(state);
    }

    private boolean isSameTatami(ImmutableBlockState first, ImmutableBlockState second) {
        Optional<Key> firstId = first.owner().keyOptional().map(k -> k.location());
        Optional<Key> secondId = second.owner().keyOptional().map(k -> k.location());
        return firstId.isPresent() && firstId.equals(secondId);
    }

    private static void refreshTatamiState(Block block) {
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
        if (!isTatamiState(state)) {
            return;
        }

        Boolean paired = getBooleanProperty(state, pairedPropertyName);
        if (!Boolean.TRUE.equals(paired)) {
            return;
        }

        BlockFace facing = getFacingFromState(state);
        Block partnerBlock = block.getRelative(facing);
        ImmutableBlockState partnerState = CraftEngineBlocks.getCustomBlockState(partnerBlock);
        if (isTatamiState(partnerState)) {
            return;
        }

        Property<Boolean> pairedProperty = getBooleanPropertyDefinition(state, pairedPropertyName);
        if (pairedProperty == null) {
            return;
        }

        ImmutableBlockState unpairedState = state.with(pairedProperty, false);
        CraftEngineBlocks.place(block.getLocation(), unpairedState, false);
    }

    private static boolean isTatamiState(ImmutableBlockState state) {
        if (state == null || state.isEmpty()) {
            return false;
        }

        return state.owner().keyOptional()
                .map(Object::toString)
                .filter(tatamiBlockId::equals)
                .isPresent();
    }

    @SuppressWarnings("unchecked")
    private static Property<Boolean> getBooleanPropertyDefinition(ImmutableBlockState state, String propertyName) {
        if (state == null || state.isEmpty()) {
            return null;
        }

        Property<?> property = state.owner().value().getProperty(propertyName);
        if (property instanceof Property<?> typedProperty) {
            return (Property<Boolean>) typedProperty;
        }
        return null;
    }

    private static Boolean getBooleanProperty(ImmutableBlockState state, String propertyName) {
        Property<Boolean> property = getBooleanPropertyDefinition(state, propertyName);
        if (property == null) {
            return null;
        }
        return state.get(property);
    }

    private static BlockFace getFacingFromState(ImmutableBlockState state) {
        if (state == null || state.isEmpty()) {
            return BlockFace.NORTH;
        }

        Property<?> property = state.owner().value().getProperty(facingPropertyName);
        if (property == null) {
            return BlockFace.NORTH;
        }

        Object facingValue = state.get(property);
        if (facingValue == null) {
            return BlockFace.NORTH;
        }

        String facingStr = facingValue.toString().toUpperCase();
        try {
            return BlockFace.valueOf(facingStr);
        } catch (IllegalArgumentException e) {
            return BlockFace.NORTH;
        }
    }

    private static String getString(Map<String, Object> arguments, String key, String defaultValue) {
        Object value = arguments != null ? arguments.get(key) : null;
        if (value != null) {
            return String.valueOf(value);
        }
        return defaultValue;
    }

    private static boolean getBoolean(Map<String, Object> arguments, String key, boolean defaultValue) {
        Object value = arguments != null ? arguments.get(key) : null;
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String string) {
            return Boolean.parseBoolean(string);
        }
        return defaultValue;
    }
}

