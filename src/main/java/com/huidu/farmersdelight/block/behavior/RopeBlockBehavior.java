package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.listener.RopeBlockListener;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.compat.ProtectionCompat;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehavior;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockSupport;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.Set;

public class RopeBlockBehavior extends FarmersDelightBlockBehavior {

    @Override
    public boolean isPathFindable(Object thisBlock, Object[] args) {
        return false;
    }

    private static final String PROP_NORTH = "north";
    private static final String PROP_SOUTH = "south";
    private static final String PROP_EAST = "east";
    private static final String PROP_WEST = "west";

    private static final BlockFace[] HORIZONTAL_FACES = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    // Blocks that never accept a rope tie even when they present a full face, matching the vanilla
    // exception list: leaves, barriers, shulker boxes, pumpkins and melons.
    private static final Set<Material> CONNECTION_EXCEPTIONS = EnumSet.of(
            Material.BARRIER,
            Material.CARVED_PUMPKIN,
            Material.JACK_O_LANTERN,
            Material.MELON,
            Material.PUMPKIN
    );

    // Every bars and glass pane block. In the mod the rope block extends IronBarsBlock, so a rope always
    // ties to any block that is an IronBarsBlock; there is no vanilla block tag and no single Bukkit
    // interface covering them, hence the material set.
    //
    // The set mirrors vanilla's IronBarsBlock and its subclasses: IronBarsBlock itself backs iron_bars and
    // glass_pane, StainedGlassPaneBlock backs the sixteen dyed panes, and WeatheringCopperBarsBlock backs
    // the eight copper bars. Every one of those blocks is named GLASS_PANE or _BARS and nothing else in the
    // registry carries either suffix, so the name test is exact. A future version that adds another
    // IronBarsBlock subclass under a different name needs this revisited.
    private static final Set<Material> PANE_BLOCKS = createPaneBlocks();

    private static Set<Material> createPaneBlocks() {
        Set<Material> panes = EnumSet.noneOf(Material.class);
        for (Material material : Material.values()) {
            if (material.isLegacy()) {
                continue;
            }
            String name = material.name();
            if (name.endsWith("GLASS_PANE") || name.endsWith("_BARS")) {
                panes.add(material);
            }
        }
        return panes;
    }

    private final Property<Boolean> northProperty;
    private final Property<Boolean> southProperty;
    private final Property<Boolean> eastProperty;
    private final Property<Boolean> westProperty;

    private RopeBlockBehavior(BlockDefinition block,
                              Property<Boolean> northProperty,
                              Property<Boolean> southProperty,
                              Property<Boolean> eastProperty,
                              Property<Boolean> westProperty) {
        super(block);
        this.northProperty = northProperty;
        this.southProperty = southProperty;
        this.eastProperty = eastProperty;
        this.westProperty = westProperty;
    }

    // The four connection properties stay optional: a rope that declares none of them is a plain single-model
    // rope, and climbing, reeling down and ringing a bell all work without them. They are resolved with the
    // value class checked, so a property declared under one of these names but not as a boolean is skipped
    // like an absent one instead of throwing out of the placement path on the first rope put down.
    public static final BlockBehaviorFactory<RopeBlockBehavior> FACTORY = (BlockDefinition block, net.momirealms.craftengine.core.plugin.config.ConfigSection section) -> {
        return new RopeBlockBehavior(
                block,
                BlockBehaviorFactory.getOptionalProperty(block, PROP_NORTH, Boolean.class),
                BlockBehaviorFactory.getOptionalProperty(block, PROP_SOUTH, Boolean.class),
                BlockBehaviorFactory.getOptionalProperty(block, PROP_EAST, Boolean.class),
                BlockBehaviorFactory.getOptionalProperty(block, PROP_WEST, Boolean.class)
        );
    };

    // Connections are decided once, here, and never re-derived afterwards. Clicking a horizontal face lets the
    // rope tie to anything that offers a full solid face on that side; clicking a vertical face restricts it to
    // ropes, panes, bars and walls. A later neighbour update always falls back to the restricted test, so a tie
    // to a solid block only ever exists as long as nothing on that side changes.
    @Override
    public ImmutableBlockState updateStateForPlacement(BlockPlaceContext context, ImmutableBlockState state) {
        BlockPos pos = context.getClickedPos();
        World world = (World) context.getLevel().platformWorld();
        boolean horizontalPlacement = context.getClickedFace().axis().isHorizontal();

        ImmutableBlockState result = state;
        if (northProperty != null) {
            result = result.with(northProperty, connectsOnPlacement(world, pos, BlockFace.NORTH, horizontalPlacement));
        }
        if (southProperty != null) {
            result = result.with(southProperty, connectsOnPlacement(world, pos, BlockFace.SOUTH, horizontalPlacement));
        }
        if (eastProperty != null) {
            result = result.with(eastProperty, connectsOnPlacement(world, pos, BlockFace.EAST, horizontalPlacement));
        }
        if (westProperty != null) {
            result = result.with(westProperty, connectsOnPlacement(world, pos, BlockFace.WEST, horizontalPlacement));
        }

        return result;
    }

    @Override
    public void placeMultiState(Object thisBlock, Object[] args) {
        
    }

    @Override
    public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) {
        if (context.getPlayer() == null) return InteractionResult.PASS;

        Player bukkitPlayer = Bukkit.getPlayer(context.getPlayer().uuid());
        if (bukkitPlayer == null) return InteractionResult.PASS;

        ItemStack hand = bukkitPlayer.getInventory().getItemInMainHand();
        // Empty hand is the bell-ringing case, which lives in useWithoutItem. CraftEngine only calls that method
        // when useOnBlock reports TRY_EMPTY_HAND (the value BlockBehavior returns by default); PASS ends the
        // dispatch here and would leave the whole bell path unreachable.
        if (hand == null || hand.getType().isAir()) return InteractionResult.TRY_EMPTY_HAND;

        String handItemId = ItemUtils.getCustomItemId(hand);
        if (handItemId == null || !state.owner().value().id().toString().equals(handItemId)) {
            return InteractionResult.PASS;
        }

        BlockPos pos = context.getClickedPos();
        World world = (World) context.getLevel().platformWorld();

        // The rope always reels straight down from the clicked rope. The mod switches the direction to the
        // clicked face while the player sneaks, but CraftEngine skips block behaviors entirely for a sneaking
        // player who is holding an item, so a sneak branch here could never run.
        int cx = pos.x();
        int cy = pos.y() - 1;
        int cz = pos.z();

        while (cy >= world.getMinHeight()) {
            Block target = world.getBlockAt(cx, cy, cz);
            if (CustomBlockUtils.hasBehavior(target, RopeBlockBehavior.class)) {
                cy--;
                continue;
            }

            // Every dead end below aborts the interaction with FAIL rather than PASS. PASS would hand the click
            // back to CraftEngine's normal item placement, which drops a rope against the clicked face — the
            // fallback the mod deliberately avoids by failing the placement outright.
            // Lava (and any other non-water fluid) blocks the reel, and so does anything the rope cannot replace.
            if (target.getType() == Material.LAVA || !target.isReplaceable()) {
                return InteractionResult.FAIL;
            }

            BlockDefinition ropeBlock = CraftEngineBlocks.byId(state.owner().value().id());
            if (ropeBlock == null) return InteractionResult.FAIL;

            BlockPos bp = new BlockPos(cx, cy, cz);
            ImmutableBlockState placementState = computeConnectionState(ropeBlock.defaultState(), world, bp);

            // R-SEC-001: this path DENYs the vanilla interaction and places the block itself via
            // CraftEngineBlocks.place, so vanilla's own WorldGuard build check never fires — gate here.
            if (!ProtectionCompat.canBuild(bukkitPlayer, target, ProtectionCompat.Feature.ROPE)) {
                return InteractionResult.FAIL;
            }

            Location placeLoc = new Location(world, cx + 0.5, cy, cz + 0.5);
            // The place call already emits the block's configured place sound, so none is played here.
            if (!CraftEngineBlocks.place(placeLoc, placementState, true)) return InteractionResult.FAIL;

            bukkitPlayer.swingMainHand();

            // CraftEngineBlocks.place is a raw block write that fires no CustomBlockPlaceEvent, so the rope
            // index has to be told about this rope directly or later neighbour changes will not refresh it.
            RopeBlockListener.syncRopeIndex(world, bp);

            FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
            if (plugin != null) {
                plugin.scheduler().runAt(placeLoc, () -> refreshAdjacentRopes(world, bp));
            }

            if (bukkitPlayer.getGameMode() != GameMode.CREATIVE) {
                hand.setAmount(hand.getAmount() - 1);
                if (hand.getAmount() <= 0) {
                    bukkitPlayer.getInventory().setItemInMainHand(null);
                }
            }

            return InteractionResult.SUCCESS_AND_CANCEL;
        }

        return InteractionResult.FAIL;
    }

    @Override
    public InteractionResult useWithoutItem(UseOnContext context, ImmutableBlockState state) {
        if (context.getPlayer() == null) {
            return InteractionResult.PASS;
        }
        Player bukkitPlayer = Bukkit.getPlayer(context.getPlayer().uuid());
        // Non-sneaking empty hand rings a bell above (as in vanilla RopeBlock); sneaking empty hand is the reel
        // path handled by RopeBlockListener, so leave it alone here.
        if (bukkitPlayer == null || bukkitPlayer.isSneaking()) {
            return InteractionResult.PASS;
        }

        World world = (World) context.getLevel().platformWorld();
        BlockPos pos = context.getClickedPos();

        // Mirror vanilla RopeBlock.useWithoutItem: walk up through a contiguous rope column (max 24 blocks) and
        // ring the first bell found. Any gap, or any block that is neither a rope nor a bell, stops the search.
        int x = pos.x();
        int z = pos.z();
        int maxDistance = Math.max(1, FarmersDelightPlugin.getInstance().getConfigInt(24, "rope.bell-ring-max-distance"));
        for (int i = 1, y = pos.y() + 1; i <= maxDistance && y < world.getMaxHeight(); i++, y++) {
            Block above = world.getBlockAt(x, y, z);
            if (above.getType() == Material.BELL) {
                // The bell can sit up to the configured distance away, so it needs its own use check: the click
                // itself is covered by the protection plugin cancelling the interact event on the rope, but the
                // bell that far away is not.
                if (!ProtectionCompat.canUse(bukkitPlayer, above, ProtectionCompat.Feature.ROPE)) {
                    return InteractionResult.PASS;
                }
                ringBell(above, bukkitPlayer);
                // The rope's appearance is not one of the blocks the client predicts an interaction for, so
                // the swing has to be sent from here.
                bukkitPlayer.swingMainHand();
                return InteractionResult.SUCCESS;
            }
            if (!CustomBlockUtils.hasBehavior(above, RopeBlockBehavior.class)) {
                return InteractionResult.PASS;
            }
        }
        return InteractionResult.PASS;
    }

    private void ringBell(Block bell, Player player) {
        Runnable ring = () -> {
            if (bell.getType() != Material.BELL || !(bell.getState() instanceof org.bukkit.block.Bell bellState)) {
                return;
            }
            BlockFace direction = bell.getBlockData() instanceof org.bukkit.block.data.Directional directional
                    ? clockwise(directional.getFacing())
                    : null;
            bellState.ring(player, direction);
        };
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        if (plugin != null) {
            plugin.scheduler().runAt(bell.getLocation(), ring);
        } else {
            ring.run();
        }
    }

    private static BlockFace clockwise(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            case WEST -> BlockFace.NORTH;
            default -> face;
        };
    }

    public static ItemStack createItemForRopeBlock(Block block) {
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
        if (state == null || state.isEmpty()) {
            return null;
        }
        return ItemUtils.createItem(state.owner().value().id());
    }

    // The tie a rope keeps for good: other ropes, iron bars, glass panes and walls. Rope identity comes from the
    // behavior rather than the material, because a custom block wears a disguise material (R-API-007).
    private static boolean tieToRopeAndWalls(Block neighbor) {
        if (CustomBlockUtils.hasBehavior(neighbor, RopeBlockBehavior.class)) {
            return true;
        }
        Material material = neighbor.getType();
        return PANE_BLOCKS.contains(material) || Tag.WALLS.isTagged(material);
    }

    // The wider tie only a fresh placement against a horizontal face can make: anything that is not on the
    // exception list and turns a full solid face towards the rope.
    private static boolean tieToAnythingValid(Block neighbor, BlockFace faceTowardsRope) {
        if (!isExceptionForConnection(neighbor)
                && neighbor.getBlockData().isFaceSturdy(faceTowardsRope, BlockSupport.FULL)) {
            return true;
        }
        return tieToRopeAndWalls(neighbor);
    }

    private static boolean isExceptionForConnection(Block neighbor) {
        Material material = neighbor.getType();
        return CONNECTION_EXCEPTIONS.contains(material)
                || Tag.LEAVES.isTagged(material)
                || Tag.SHULKER_BOXES.isTagged(material);
    }

    private static boolean connectsOnPlacement(World world, BlockPos pos, BlockFace direction, boolean horizontalPlacement) {
        Block neighbor = world.getBlockAt(
                pos.x() + direction.getModX(),
                pos.y() + direction.getModY(),
                pos.z() + direction.getModZ()
        );
        return horizontalPlacement
                ? tieToAnythingValid(neighbor, direction.getOppositeFace())
                : tieToRopeAndWalls(neighbor);
    }

    private static Property<Boolean> connectionProperty(ImmutableBlockState state, BlockFace face) {
        String name = switch (face) {
            case NORTH -> PROP_NORTH;
            case SOUTH -> PROP_SOUTH;
            case EAST -> PROP_EAST;
            case WEST -> PROP_WEST;
            default -> null;
        };
        if (name == null) {
            return null;
        }
        return BlockBehaviorFactory.getOptionalProperty(state.owner().value(), name, Boolean.class);
    }

    // Connections for a rope that is put in place programmatically rather than by a click. That is always the
    // reel-down case (and the rope a hanging tomato leaves behind), which the mod resolves through a downwards
    // placement context, so it takes the restricted rope/pane/wall test on every side.
    public static ImmutableBlockState computeConnectionState(ImmutableBlockState state, World world, BlockPos pos) {
        ImmutableBlockState result = state;
        for (BlockFace face : HORIZONTAL_FACES) {
            Property<Boolean> property = connectionProperty(state, face);
            if (property == null) {
                continue;
            }
            Block neighbor = world.getBlockAt(
                    pos.x() + face.getModX(),
                    pos.y() + face.getModY(),
                    pos.z() + face.getModZ()
            );
            result = result.with(property, tieToRopeAndWalls(neighbor));
        }
        return result;
    }

    // Re-derives the one connection each adjacent rope has towards pos, after whatever stands there changed.
    // Only that side is touched, and only with the restricted rope/pane/wall test: the wider solid-face tie is
    // made at placement time and is never restored by a neighbour update, while the connections a rope holds on
    // its other three sides are none of this update's business.
    public static void refreshAdjacentRopes(World world, BlockPos pos) {
        // Raw CraftEngine writes fire no place or break event, so this is also where the rope index learns
        // whether pos still holds a rope. Every programmatic rope write is followed by a refresh from here.
        RopeBlockListener.syncRopeIndex(world, pos);

        Block changed = world.getBlockAt(pos.x(), pos.y(), pos.z());
        boolean connects = tieToRopeAndWalls(changed);

        for (BlockFace face : HORIZONTAL_FACES) {
            Block neighbor = world.getBlockAt(
                    pos.x() + face.getModX(),
                    pos.y() + face.getModY(),
                    pos.z() + face.getModZ()
            );
            ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(neighbor);
            if (state == null || state.isEmpty() || !CustomBlockUtils.hasBehavior(state, RopeBlockBehavior.class)) {
                continue;
            }

            Property<Boolean> property = connectionProperty(state, face.getOppositeFace());
            if (property == null) {
                continue;
            }

            ImmutableBlockState updated = state.with(property, connects);
            // with returns the same state when the value is unchanged, so an unaffected rope is left alone
            // instead of being rewritten and resent.
            if (updated == state) {
                continue;
            }

            CraftEngineBlocks.place(neighbor.getLocation(), updated, false);
        }
    }
}

