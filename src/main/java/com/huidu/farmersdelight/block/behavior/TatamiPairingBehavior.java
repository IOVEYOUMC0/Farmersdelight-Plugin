package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.util.BehaviorArgParser;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.compat.CraftEngineAdapter;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.plugin.config.KnownResourceException;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;

public class TatamiPairingBehavior extends FarmersDelightBlockBehavior {
    private static final BlockFace[] ORTHOGONAL_FACES = {
            BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN
    };

    @Override
    public boolean isPathFindable(Object thisBlock, Object[] args) {
        return false;
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

    public static final BlockBehaviorFactory<TatamiPairingBehavior> FACTORY = new BlockBehaviorFactory<TatamiPairingBehavior>() {
        @Override
        public TatamiPairingBehavior create(BlockDefinition block, ConfigSection section) {
            Map<String, Object> arguments = section != null ? section.values() : Map.of();
            tatamiBlockId = BehaviorArgParser.getString(arguments, "block-id", tatamiBlockId);
            facingPropertyName = BehaviorArgParser.getString(arguments, "facing-property", facingPropertyName);
            pairedPropertyName = BehaviorArgParser.getString(arguments, "pair.property", "paired-property", pairedPropertyName);
            boolean pairWhileSneaking = BehaviorArgParser.getBoolean(arguments, "pair.while-sneaking", false);

            // Both properties carry the pairing, which is everything this behavior does: without either one no
            // mat ever pairs, no weave orientation is written and no partner is ever reset, while the block
            // still places and looks like a working tatami. A block that declares this behavior without them
            // aborts its own load here with the config node and the property name.
            String path = section != null ? section.path() : Constants.BEHAVIOR_TATAMI;
            // Looked up by name only: the facing value is read and written as text, so any property type whose
            // values spell out directions is accepted.
            Property<?> facingProperty = block.getProperty(facingPropertyName);
            if (facingProperty == null) {
                throw new KnownResourceException(
                        "resource.block.behavior.missing_property", path, facingPropertyName);
            }
            // The paired flag is set with a Boolean, so a wrong-typed property would throw on the first
            // placement instead of at load; require the type here.
            Property<Boolean> pairedProperty =
                    BlockBehaviorFactory.getProperty(path, block, pairedPropertyName, Boolean.class);

            return new TatamiPairingBehavior(block, facingProperty, pairedProperty, pairWhileSneaking);
        }
    };

    @Override
    public ImmutableBlockState updateStateForPlacement(BlockPlaceContext context, ImmutableBlockState state) {
        Direction facing = context.getClickedFace().opposite();
        return withPropertyValue(state, facingProperty, facing.name().toLowerCase(Locale.ROOT));
    }

    @Override
    public void placeMultiState(Object thisBlock, Object[] args) {
        if (args.length >= 5) {
            World world = CraftEngineAdapter.toWorld(args[0]);
            // args[1] is the placement position CraftEngine passes as a native Minecraft BlockPos, not the
            // CraftEngine BlockPos type, so convert through the adapter instead of an instanceof cast.
            BlockPos pos = CraftEngineAdapter.toBlockPos(args[1]);
            if (pos == null || world == null) {
                return;
            }

            Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
            ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
            if (state == null || state.isEmpty() || (!pairWhileSneaking && isPlacerSneaking(args[3]))) {
                return;
            }

            if (pairWithNeighbor(world, pos, state)) {
                // Replace the just-placed block with paired=true so both halves stay visually in sync.
                CraftEngineBlocks.place(block.getLocation(), state.with(pairedProperty, true), false);
            }
        }
    }

    @Override
    public Object updateShape(Object thisBlock, Object[] args) {
        if (args.length < 7) {
            return args[0];
        }
        ImmutableBlockState state = BlockStateUtils.getOptionalCustomBlockState(args[0]).orElse(null);
        if (!isTatamiState(state) || !Boolean.TRUE.equals(state.get(pairedProperty))) {
            return args[0];
        }
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[3]);
        BlockPos neighborPos = CraftEngineAdapter.toBlockPos(args[5]);
        if (pos == null || neighborPos == null) {
            return args[0];
        }
        BlockFace facing = getFacing(state);
        boolean facingMatch = pos.x() + facing.getModX() == neighborPos.x()
                && pos.y() + facing.getModY() == neighborPos.y()
                && pos.z() + facing.getModZ() == neighborPos.z();
        ImmutableBlockState neighborState = BlockStateUtils.getOptionalCustomBlockState(args[6]).orElse(null);
        boolean partnerGone = facingMatch && !(isTatamiState(neighborState) && isSameTatami(state, neighborState));
        if (!partnerGone) {
            return args[0];
        }
        return state.with(pairedProperty, false).customBlockState().minecraftState();
    }

    @Override
    public void neighborChanged(Object thisBlock, Object[] args) {
        if (args.length < 3) {
            return;
        }
        World world = CraftEngineAdapter.toWorld(args[1]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
        if (world == null || pos == null) {
            return;
        }

        Block self = world.getBlockAt(pos.x(), pos.y(), pos.z());
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(self);
        if (!isTatamiState(state) || !Boolean.TRUE.equals(state.get(pairedProperty))) {
            return;
        }

        Block partner = self.getRelative(getFacing(state));
        ImmutableBlockState partnerState = CraftEngineBlocks.getCustomBlockState(partner);
        boolean partnerGone = !(isTatamiState(partnerState) && isSameTatami(state, partnerState));
        if (!partnerGone) {
            return;
        }
        CraftEngineBlocks.place(self.getLocation(), state.with(pairedProperty, false), false);
    }

    public static void resetFacingNeighbors(Location brokenLocation, String source) {
        if (brokenLocation == null || brokenLocation.getWorld() == null) {
            return;
        }
        World world = brokenLocation.getWorld();
        Block center = world.getBlockAt(brokenLocation);
        for (BlockFace face : ORTHOGONAL_FACES) {
            Block neighbor = center.getRelative(face);
            // A neighbour in a chunk this thread does not own, or in an unloaded one, is left alone: unpairing
            // it would mean reading or writing another region from a break handler. Residency alone does not
            // prove ownership, so the region check comes first, and a skipped neighbour stays paired until the
            // neighbour update that runs when the border is crossed.
            if (!Bukkit.isOwnedByCurrentRegion(neighbor)) {
                continue;
            }
            ImmutableBlockState nState = CustomBlockUtils.getStateIfResident(neighbor);
            if (!isTatamiState(nState)) {
                continue;
            }
            Property<Boolean> paired = pairedPropertyOf(nState);
            if (paired == null || !Boolean.TRUE.equals(nState.get(paired))) {
                continue;
            }
            Block partner = neighbor.getRelative(getFacingFromState(nState));
            if (partner.getX() == center.getX() && partner.getY() == center.getY() && partner.getZ() == center.getZ()) {
                CraftEngineBlocks.place(neighbor.getLocation(), nState.with(paired, false), false);
            }
        }
    }

    // CraftEngine's Player does not expose getBukkitEntity through a callable type, so this stays
    // reflective; the lookup itself is cached because it runs once per placement.
    private static volatile Method getBukkitEntityMethod;

    private static boolean isPlacerSneaking(Object playerArg) {
        if (playerArg == null) {
            return false;
        }
        if (playerArg instanceof Player cePlayer) {
            return cePlayer.isSecondaryUseActive();
        }
        try {
            Method method = getBukkitEntityMethod;
            if (method == null) {
                method = playerArg.getClass().getMethod("getBukkitEntity");
                getBukkitEntityMethod = method;
            }
            Object bukkitEntity = method.invoke(playerArg);
            if (bukkitEntity instanceof org.bukkit.entity.Player bukkitPlayer) {
                return bukkitPlayer.isSneaking();
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return false;
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

        Boolean neighborPaired = neighborState.get(pairedProperty);
        if (Boolean.TRUE.equals(neighborPaired)) {
            return false;
        }

        CraftEngineBlocks.place(
                neighborBlock.getLocation(),
                withFacingAndPair(neighborState, facing.getOppositeFace()),
                false
        );
        return true;
    }

    private ImmutableBlockState withFacingAndPair(ImmutableBlockState state, BlockFace facing) {
        ImmutableBlockState result =
                withPropertyValue(state, facingProperty, facing.name().toLowerCase(Locale.ROOT));
        return result.with(pairedProperty, true);
    }

    private ImmutableBlockState withPropertyValue(ImmutableBlockState state, Property<?> property, String valueName) {
        Comparable<?> value = property.valueByName(valueName);
        return value == null ? state : ImmutableBlockState.with(state, property, value);
    }

    @Override
    public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) {
        return InteractionResult.PASS;
    }

    private BlockFace getFacing(ImmutableBlockState state) {
        return getFacingFromState(state);
    }

    private boolean isSameTatami(ImmutableBlockState first, ImmutableBlockState second) {
        Optional<Key> firstId = first.owner().keyOptional().map(k -> k.location());
        Optional<Key> secondId = second.owner().keyOptional().map(k -> k.location());
        return firstId.isPresent() && firstId.equals(secondId);
    }

    private static boolean isTatamiState(ImmutableBlockState state) {
        if (state == null || state.isEmpty()) {
            return false;
        }

        return state.owner().keyOptional()
                .map(k -> k.location().toString())
                .filter(tatamiBlockId::equals)
                .isPresent();
    }

    private static Property<Boolean> pairedPropertyOf(ImmutableBlockState state) {
        if (state == null || state.isEmpty()) {
            return null;
        }
        return BlockBehaviorFactory.getOptionalProperty(state.owner().value(), pairedPropertyName, Boolean.class);
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

        String facingStr = facingValue.toString().toUpperCase(Locale.ROOT);
        try {
            return BlockFace.valueOf(facingStr);
        } catch (IllegalArgumentException e) {
            return BlockFace.NORTH;
        }
    }

}
