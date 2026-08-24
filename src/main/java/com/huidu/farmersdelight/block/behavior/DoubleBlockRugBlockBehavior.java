package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.util.BehaviorArgParser;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.compat.CraftEngineAdapter;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

// Generic "double block" behavior for a 2-cell mat: one placement spawns a head + foot half in adjacent
// cells along the player's facing, and removing either half tears down the partner so no orphan cell
// survives. Facing and part are looked up by name from config so any property pair can drive the pairing.
public class DoubleBlockRugBlockBehavior extends RugBlockBehavior {

    private final Property<?> facingProperty;
    private final Property<?> partProperty;
    private final Set<String> partnerIds;

    private DoubleBlockRugBlockBehavior(
            BlockDefinition block,
            Property<?> facingProperty,
            Property<?> partProperty,
            Set<String> partnerIds) {
        super(block);
        this.facingProperty = facingProperty;
        this.partProperty = partProperty;
        this.partnerIds = partnerIds;
    }

    public static final BlockBehaviorFactory<DoubleBlockRugBlockBehavior> FACTORY = new BlockBehaviorFactory<DoubleBlockRugBlockBehavior>() {
        @Override
        public DoubleBlockRugBlockBehavior create(BlockDefinition block, net.momirealms.craftengine.core.plugin.config.ConfigSection section) {
            Map<String, Object> arguments = section != null ? section.values() : Map.of();
            String facingPropertyName = BehaviorArgParser.getString(arguments, "facing-property", "facing");
            String partPropertyName = BehaviorArgParser.getString(arguments, "part-property", "part");
            Property<?> facingProperty = block.getProperty(facingPropertyName);
            Property<?> partProperty = block.getProperty(partPropertyName);
            Set<String> partnerIds = parseStringList(arguments.get("partner-ids"));
            if (partnerIds.isEmpty()) {
                partnerIds.add(block.id().namespace() + ":" + block.id().value());
            }

            String path = section != null ? section.path() : Constants.BEHAVIOR_DOUBLE_BLOCK;
            if (facingProperty == null || partProperty == null) {
                throw new net.momirealms.craftengine.core.plugin.config.KnownResourceException(
                        "resource.block.behavior.missing_property", path, "facing/part");
            }
            return new DoubleBlockRugBlockBehavior(block, facingProperty, partProperty, partnerIds);
        }
    };

    // Writes the player's horizontal placement direction into the facing property so the head carries the
    // same facing as the foot spawned beside it. The foot cell is validated here so an occupied cell rejects
    // the placement atomically - CE never places a lone head that later gets torn down.
    @Override
    public ImmutableBlockState updateStateForPlacement(BlockPlaceContext context, ImmutableBlockState state) {
        BlockFace face = lookingHorizontalFace(context);
        if (face == null) {
            return null;
        }
        if (footCellBlocked(context, face)) {
            return null;
        }
        return withFacing(state, face);
    }

    @Override
    public void placeMultiState(Object thisBlock, Object[] args) {
        if (args.length < 5) {
            return;
        }
        World world = CraftEngineAdapter.toWorld(args[0]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[1]);
        if (world == null || pos == null) {
            return;
        }
        Block self = world.getBlockAt(pos.x(), pos.y(), pos.z());
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(self);
        if (state == null || state.isEmpty()) {
            return;
        }
        placeDoubleBlock(world, self, state);
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
        if (state == null || state.isEmpty()) {
            return;
        }
        Block partner = partnerOf(self, state);
        ImmutableBlockState partnerState = partner == null ? null : CraftEngineBlocks.getCustomBlockState(partner);
        if (partner == null || partnerState == null || partnerState.isEmpty() || !isPartner(partnerState)) {
            teardownSelf(self);
        }
    }

    @Override
    public void affectNeighborsAfterRemoval(Object thisBlock, Object[] args) {
        handleRemoval(args);
    }

    @Override
    public void spawnAfterBreak(Object thisBlock, Object[] args) {
        handleRemoval(args);
    }

    // Removing one half also removes its paired partner so no orphan half is ever left floating.
    private void handleRemoval(Object[] args) {
        if (args == null || args.length < 3) {
            return;
        }
        World world = CraftEngineAdapter.toWorld(args[1]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[2]);
        if (world == null || pos == null) {
            return;
        }
        Block removed = world.getBlockAt(pos.x(), pos.y(), pos.z());
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(removed);
        if (state == null || state.isEmpty()) {
            return;
        }
        Block partner = partnerOf(removed, state);
        if (partner != null) {
            ImmutableBlockState partnerState = CraftEngineBlocks.getCustomBlockState(partner);
            if (partnerState != null && !partnerState.isEmpty() && isPartner(partnerState)) {
                partner.setType(Material.AIR, false);
            }
        }
    }

    // The head cell spawns the foot along its facing; the foot (already carrying facing from the shared
    // placement) is placed next to the head, so one item yields two cells. The foot cell was verified free
    // at placement time, so no tear-down is needed here.
    private void placeDoubleBlock(World world, Block self, ImmutableBlockState state) {
        if (!isPart(state, "head")) {
            // Already a foot half: its head partner was placed by the other cell, do not recurse.
            return;
        }
        BlockFace facing = facingFromState(state);
        Block partner = self.getRelative(facing);
        CraftEngineBlocks.place(partner.getLocation(), withPart(state, "foot"), false);
    }

    private boolean footCellBlocked(BlockPlaceContext context, BlockFace facing) {
        if (!(context.getLevel().platformWorld() instanceof World world)) {
            return true;
        }
        net.momirealms.craftengine.core.world.BlockPos clicked = context.getClickedPos();
        Block foot = world.getBlockAt(clicked.x(), clicked.y(), clicked.z()).getRelative(facing);
        return !foot.getType().isAir();
    }

    private BlockFace facingFromState(ImmutableBlockState state) {
        Object value = state.get(facingProperty);
        if (value == null) {
            return BlockFace.NORTH;
        }
        try {
            return BlockFace.valueOf(value.toString().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BlockFace.NORTH;
        }
    }

    // Prefers the player's horizontal facing (their yaw). That stays stable when placing on a floor or
    // ceiling, where the raw look direction is vertical and the nearest-looking-axis based fallback would
    // degrade to one fixed horizontal direction regardless of where the player turns. The clicked face
    // only backs up non-player or wall placements.
    private BlockFace lookingHorizontalFace(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection();
        if (facing != null && facing.axis().isHorizontal()) {
            return fromDirection(facing);
        }
        return fromDirection(context.getClickedFace());
    }

    @SuppressWarnings("unchecked")
    private ImmutableBlockState withFacing(ImmutableBlockState state, BlockFace face) {
        if (facingProperty.valueClass() != Direction.class) {
            return state;
        }
        return state.with((Property<Direction>) facingProperty, toDirection(face));
    }

    private Block partnerOf(Block self, ImmutableBlockState state) {
        BlockFace facing = isPart(state, "head")
                ? facingFromState(state)
                : facingFromState(state).getOppositeFace();
        return self.getRelative(facing);
    }

    private boolean isPart(ImmutableBlockState state, String value) {
        return state != null
                && state.get(partProperty) != null
                && value.equals(state.get(partProperty).toString());
    }

    private ImmutableBlockState withPart(ImmutableBlockState state, String value) {
        return withPropertyValue(state, partProperty, value);
    }

    private boolean isPartner(ImmutableBlockState state) {
        return matchesAnyId(state, partnerIds);
    }

    private void teardownSelf(Block self) {
        self.setType(Material.AIR, false);
    }
}