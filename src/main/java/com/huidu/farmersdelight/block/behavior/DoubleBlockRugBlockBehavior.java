package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.util.BehaviorArgParser;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.compat.CraftEngineAdapter;
import com.huidu.farmersdelight.util.compat.ProtectionCompat;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.plugin.config.KnownResourceException;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

// Generic "double block" behavior for a 2-cell mat: one placement spawns a head + foot half in adjacent
// cells along the player's facing, and removing either half tears down the partner so no orphan cell
// survives. Facing and part are looked up by name from config so any property pair can drive the pairing.
public class DoubleBlockRugBlockBehavior extends RugBlockBehavior {

    private final Property<Direction> facingProperty;
    private final Property<?> partProperty;
    private final Set<String> partnerIds;

    private DoubleBlockRugBlockBehavior(
            BlockDefinition block,
            Property<Direction> facingProperty,
            Property<?> partProperty,
            Set<String> partnerIds) {
        super(block);
        this.facingProperty = facingProperty;
        this.partProperty = partProperty;
        this.partnerIds = partnerIds;
    }

    public static final BlockBehaviorFactory<DoubleBlockRugBlockBehavior> FACTORY = new BlockBehaviorFactory<DoubleBlockRugBlockBehavior>() {
        @Override
        public DoubleBlockRugBlockBehavior create(BlockDefinition block, ConfigSection section) {
            Map<String, Object> arguments = section != null ? section.values() : Map.of();
            String facingPropertyName = BehaviorArgParser.getString(arguments, "facing-property", "facing");
            String partPropertyName = BehaviorArgParser.getString(arguments, "part-property", "part");
            Property<Direction> facingProperty = BlockBehaviorFactory.getOptionalProperty(
                    block, facingPropertyName, Direction.class);
            Property<?> partProperty = block.getProperty(partPropertyName);
            Set<String> partnerIds = new HashSet<>(BehaviorArgParser.getStringList(arguments, "partner-ids"));
            if (partnerIds.isEmpty()) {
                partnerIds.add(block.id().namespace() + ":" + block.id().value());
            }

            String path = section != null ? section.path() : Constants.BEHAVIOR_DOUBLE_BLOCK;
            if (facingProperty == null || partProperty == null) {
                throw new KnownResourceException(
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
        // The foot cell is written by CraftEngineBlocks.place in placeMultiState, which bypasses the
        // vanilla build check the head cell went through. Reject the whole placement here (atomically,
        // before either cell exists) when the second cell falls in protected land.
        if (!footCellAllowed(context, face)) {
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
        if (partner == null) {
            teardownSelf(self);
            return;
        }
        if (!partner.getWorld().isChunkLoaded(partner.getX() >> 4, partner.getZ() >> 4)) {
            // The other half sits in a chunk that is not resident: reading its state would load that chunk, and
            // treating it as missing would wrongly tear this half down. The pair is re-checked when the
            // partner's chunk loads again.
            return;
        }
        ImmutableBlockState partnerState = CustomBlockUtils.getStateIfResident(partner);
        if (partnerState == null || partnerState.isEmpty() || !isPartner(partnerState)) {
            teardownSelf(self);
        }
    }

    // The break hook is the only removal callback that carries the player, so the player-driven teardown
    // happens here: the permission check and the clear run in that order, and the destruction state is
    // returned as it arrived because the interceptor hands this value back to the caller.
    @Override
    public Object playerWillDestroy(Object thisBlock, Object[] args) {
        if (args == null || args.length < 4) {
            return args == null || args.length < 3 ? null : args[2];
        }
        World world = CraftEngineAdapter.toWorld(args[0]);
        BlockPos pos = CraftEngineAdapter.toBlockPos(args[1]);
        if (world != null && pos != null && args[3] instanceof Player player) {
            Block broken = world.getBlockAt(pos.x(), pos.y(), pos.z());
            ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(broken);
            if (state != null && !state.isEmpty()) {
                clearPartner(partnerOf(broken, state), player);
            }
        }
        return args[2];
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
        // No player is attached to these callbacks, so there is no permission subject to check: they clear
        // the partner as before, while a player-driven break goes through playerWillDestroy instead.
        clearPartner(partner, null);
    }

    // Clears the paired half. Residency, region ownership and, when a player drove the break, that player's
    // build permission are checked first; a cell that fails any of them is left to its own next update.
    private void clearPartner(Block partner, Player breaker) {
        if (partner == null) {
            return;
        }
        if (!partner.getWorld().isChunkLoaded(partner.getX() >> 4, partner.getZ() >> 4)) {
            return;
        }
        if (!Bukkit.isOwnedByCurrentRegion(partner)) {
            return;
        }
        // The teardown is a block state change, so a player who may not build at the partner cell keeps it
        // standing; that half is left for them or an administrator to clear by hand.
        if (breaker != null && !ProtectionCompat.canPlace(breaker, partner, (ProtectionCompat.Feature) null)) {
            return;
        }
        ImmutableBlockState partnerState = CraftEngineBlocks.getCustomBlockState(partner);
        if (partnerState != null && !partnerState.isEmpty() && isPartner(partnerState)) {
            partner.setType(Material.AIR, false);
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
        // The foot cell can belong to another region, and the place below writes on the calling thread. Skip
        // it: placing into a region this thread does not own would touch a foreign block.
        if (!Bukkit.isOwnedByCurrentRegion(partner)) {
            return;
        }
        CraftEngineBlocks.place(partner.getLocation(), withPart(state, "foot"), false);
    }

    private boolean footCellAllowed(BlockPlaceContext context, BlockFace facing) {
        if (context.getPlayer() == null || !(context.getLevel().platformWorld() instanceof World world)) {
            return true;
        }
        Player bukkitPlayer = ItemUtils.getBukkitPlayer(context.getPlayer());
        if (bukkitPlayer == null) {
            return true;
        }
        BlockPos clicked = context.getClickedPos();
        Block foot = world.getBlockAt(clicked.x(), clicked.y(), clicked.z()).getRelative(facing);
        return ProtectionCompat.canBuild(bukkitPlayer, foot, (String) null);
    }

    private boolean footCellBlocked(BlockPlaceContext context, BlockFace facing) {
        if (!(context.getLevel().platformWorld() instanceof World world)) {
            return true;
        }
        BlockPos clicked = context.getClickedPos();
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

    private ImmutableBlockState withFacing(ImmutableBlockState state, BlockFace face) {
        return state.with(facingProperty, toDirection(face));
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
