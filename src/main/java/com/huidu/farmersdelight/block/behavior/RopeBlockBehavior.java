package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.listener.RopeBlockListener;
import com.huidu.farmersdelight.util.BehaviorArgParser;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.compat.CraftEngineAdapter;
import com.huidu.farmersdelight.util.compat.ProtectionCompat;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
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
import org.bukkit.block.Bell;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.Map;

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

    // Ropes connect to bars and glass panes. Bukkit has no common tag or interface for them,
    // so recognize GLASS_PANE, dyed panes and _BARS materials by name.
    // Additional bar or pane types with different names require extending this predicate.
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

    private enum ConnectionMode {
        RESTRICTED,
        SOLID_FACE;

        private static ConnectionMode parse(String value) {
            if (value == null) {
                return RESTRICTED;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
                case "solid", "solid_face", "face" -> SOLID_FACE;
                default -> RESTRICTED;
            };
        }
    }

    private enum PlacementMode {
        VANILLA,
        RESTRICTED,
        SOLID_FACE;

        private static PlacementMode parse(String value) {
            if (value == null) {
                return VANILLA;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
                case "restricted", "rope_only", "connectors" -> RESTRICTED;
                case "solid", "solid_face", "face" -> SOLID_FACE;
                default -> VANILLA;
            };
        }
    }

    private final Property<Boolean> northProperty;
    private final Property<Boolean> southProperty;
    private final Property<Boolean> eastProperty;
    private final Property<Boolean> westProperty;
    private final PlacementMode placementMode;
    private final ConnectionMode connectionMode;
    private final ConfiguredBlockSet connectorBlocks;
    private final FarmersDelightPlugin plugin;
    private final ConfiguredBlockSet exceptionBlocks;

    private RopeBlockBehavior(FarmersDelightPlugin plugin,
                              BlockDefinition block,
                              Property<Boolean> northProperty,
                              Property<Boolean> southProperty,
                              Property<Boolean> eastProperty,
                              Property<Boolean> westProperty,
                              PlacementMode placementMode,
                              ConnectionMode connectionMode,
                              ConfiguredBlockSet connectorBlocks,
                              ConfiguredBlockSet exceptionBlocks) {
        super(block);
        this.plugin = plugin;
        this.northProperty = northProperty;
        this.southProperty = southProperty;
        this.eastProperty = eastProperty;
        this.westProperty = westProperty;
        this.placementMode = placementMode;
        this.connectionMode = connectionMode;
        this.connectorBlocks = connectorBlocks;
        this.exceptionBlocks = exceptionBlocks;
    }

    // The four connection properties stay optional: a rope that declares none of them is a plain single-model
    // rope, and climbing, reeling down and ringing a bell all work without them. They are resolved with the
    // value class checked, so a property declared under one of these names but not as a boolean is skipped
    // like an absent one instead of throwing out of the placement path on the first rope put down.
    public static final BlockBehaviorFactory<RopeBlockBehavior> FACTORY = (BlockDefinition block, ConfigSection section) -> {
        // Runs while CraftEngine parses the pack, which is always after this plugin enabled.
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        Map<String, Object> arguments = section != null ? section.values() : Map.of();
        Object connectorRaw = BehaviorArgParser.getRaw(arguments, "connector-blocks");
        Object exceptionRaw = BehaviorArgParser.getRaw(arguments, "connection-exceptions");
        return new RopeBlockBehavior(
                plugin,
                block,
                BlockBehaviorFactory.getOptionalProperty(block, PROP_NORTH, Boolean.class),
                BlockBehaviorFactory.getOptionalProperty(block, PROP_SOUTH, Boolean.class),
                BlockBehaviorFactory.getOptionalProperty(block, PROP_EAST, Boolean.class),
                BlockBehaviorFactory.getOptionalProperty(block, PROP_WEST, Boolean.class),
                PlacementMode.parse(BehaviorArgParser.getString(arguments, "placement-mode", "vanilla")),
                ConnectionMode.parse(BehaviorArgParser.getString(arguments, "connection-mode", "restricted")),
                connectorRaw == null ? ConfiguredBlockSet.EMPTY : ConfiguredBlockSet.parse(connectorRaw),
                exceptionRaw == null ? null : ConfiguredBlockSet.parse(exceptionRaw)
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

    // A rope re-derives its connection towards whatever changed next to it. These are the NMS hooks the
    // block itself receives, and they replace a BlockPhysicsEvent listener that had to watch every block
    // update in the world and then ask whether a rope was nearby. Both are hooked because the two fire from
    // different paths -- neighborChanged from updateNeighborsAt, updateShape from updateNeighbourShapes --
    // and the refresh queue collapses a double hit on the same position.
    @Override
    public void neighborChanged(Object thisBlock, Object[] args) {
        if (args.length < 3) {
            return;
        }
        RopeBlockListener.queueNeighborRefresh(
                CraftEngineAdapter.toWorld(args[1]), CraftEngineAdapter.toBlockPos(args[2]));
    }

    @Override
    public Object updateShape(Object thisBlock, Object[] args) {
        if (args.length < 7) {
            return args[0];
        }
        RopeBlockListener.queueNeighborRefresh(
                CraftEngineAdapter.toWorld(args[1]), CraftEngineAdapter.toBlockPos(args[3]));
        return args[0];
    }


    @Override
    public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) {
        if (context.getPlayer() == null) return InteractionResult.PASS;

        Player bukkitPlayer = ItemUtils.getBukkitPlayer(context.getPlayer());
        if (bukkitPlayer == null) return InteractionResult.PASS;

        ItemStack hand = ItemUtils.getItemInHand(bukkitPlayer, context.getHand());
        // Empty hand is the bell-ringing case. CraftEngine only calls useWithoutItem when useOnBlock reports
        // TRY_EMPTY_HAND, and it decides for itself whether it does; ringing from here as well means a rope whose
        // top holds a bell always answers the click — the reported "the bell at the top of the rope does not
        // ring" defect. Vanilla is untouched: this only runs for a click that landed on the plugin's rope.
        if (hand == null || hand.getType().isAir()) {
            if (tryRingBellAbove(bukkitPlayer, (World) context.getLevel().platformWorld(),
                    context.getClickedPos())) {
                return InteractionResult.SUCCESS_AND_CANCEL;
            }
            return InteractionResult.TRY_EMPTY_HAND;
        }

        String handItemId = ItemUtils.getCustomItemId(hand);
        if (handItemId == null || !state.owner().value().id().toString().equals(handItemId)) {
            return InteractionResult.PASS;
        }

        BlockPos pos = context.getClickedPos();
        World world = (World) context.getLevel().platformWorld();

        // Reel straight down from the clicked rope. CraftEngine skips block behaviors for
        // sneaking players holding items, so that input is handled by item placement instead.
        int cx = pos.x();
        int cy = pos.y() - 1;
        int cz = pos.z();

        while (cy >= world.getMinHeight()) {
            Block target = world.getBlockAt(cx, cy, cz);
            if (CustomBlockUtils.hasBehavior(target, RopeBlockBehavior.class)) {
                cy--;
                continue;
            }

            // Return FAIL at dead ends to prevent normal item placement against the clicked face.
            // Non-water fluids and blocks the rope cannot replace stop the downward reel.
            if (target.getType() == Material.LAVA || !target.isReplaceable()) {
                return InteractionResult.FAIL;
            }

            BlockDefinition ropeBlock = CraftEngineBlocks.byId(state.owner().value().id());
            if (ropeBlock == null) return InteractionResult.FAIL;

            BlockPos bp = new BlockPos(cx, cy, cz);
            ImmutableBlockState placementState = computeConnectionState(ropeBlock.defaultState(), world, bp);

            // This path cancels native interaction and places directly through CraftEngineBlocks.place.
            // Check protection here because the native placement permission check will not run.
            if (!ProtectionCompat.canPlace(bukkitPlayer, target, ProtectionCompat.Feature.ROPE)) {
                return InteractionResult.FAIL;
            }

            Location placeLoc = new Location(world, cx + 0.5, cy, cz + 0.5);
            // The place call already emits the block's configured place sound, so none is played here.
            if (!CraftEngineBlocks.place(placeLoc, placementState, true)) return InteractionResult.FAIL;

            ItemUtils.swingHand(bukkitPlayer, context.getHand());

            // CraftEngineBlocks.place is a raw block write that fires no CustomBlockPlaceEvent, so the rope
            // index has to be told about this rope directly or later neighbour changes will not refresh it.
            RopeBlockListener.syncRopeIndex(world, bp);

            if (plugin != null) {
                plugin.scheduler().runAt(placeLoc, () -> refreshAdjacentRopes(world, bp));
            }

            if (bukkitPlayer.getGameMode() != GameMode.CREATIVE) {
                hand.setAmount(hand.getAmount() - 1);
                if (hand.getAmount() <= 0) {
                    bukkitPlayer.getInventory().setItem(context.getHand() == InteractionHand.OFF_HAND
                            ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND, null);
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
        Player bukkitPlayer = ItemUtils.getBukkitPlayer(context.getPlayer());
        // Non-sneaking empty hand rings a bell above (as in vanilla RopeBlock); sneaking empty hand is the reel
        // path handled by RopeBlockListener, so leave it alone here.
        if (bukkitPlayer == null || bukkitPlayer.isSneaking()) {
            return InteractionResult.PASS;
        }

        World world = (World) context.getLevel().platformWorld();
        return tryRingBellAbove(bukkitPlayer, world, context.getClickedPos())
                ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    /**
     * Rings the first bell above the clicked rope, if there is one, and reports whether it rang.
     *
     * Upstream parity: walk up through a contiguous rope column (max rope.bell-ring-max-distance
     * blocks) and ring the first bell found; any gap, or any block that is neither a rope nor a bell, ends the
     * search. A click on anything but the plugin's rope never reaches here, so a vanilla bell or any other block keeps
     * its own interaction.
     */
    private boolean tryRingBellAbove(Player player, World world, BlockPos pos) {
        if (player == null || world == null || pos == null) {
            return false;
        }
        int maxDistance = Math.max(1, plugin.getConfigInt(24, "rope.bell-ring-max-distance"));
        int x = pos.x();
        int z = pos.z();
        int bellOffset = RopeBellScan.findBellOffset(maxDistance,
                offset -> CustomBlockUtils.hasBehavior(world.getBlockAt(x, pos.y() + 1 + offset, z),
                        RopeBlockBehavior.class),
                offset -> world.getBlockAt(x, pos.y() + 1 + offset, z).getType() == Material.BELL);
        if (bellOffset < 0) {
            return false;
        }

        Block bell = world.getBlockAt(x, pos.y() + 1 + bellOffset, z);
        // The bell can sit up to the configured distance away, so it needs its own use check.
        if (!ProtectionCompat.canUse(player, bell, ProtectionCompat.Feature.ROPE)) {
            return false;
        }
        ringBell(bell, player);
        // The rope's appearance is not one of the blocks the client predicts an interaction for, so the swing
        // has to be sent from here.
        player.swingMainHand();
        return true;
    }

    private void ringBell(Block bell, Player player) {
        Runnable ring = () -> {
            if (bell.getType() != Material.BELL || !(bell.getState() instanceof Bell bellState)) {
                return;
            }
            BlockFace direction = bell.getBlockData() instanceof Directional directional
                    ? clockwise(directional.getFacing())
                    : null;
            bellState.ring(player, direction);
        };
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

    // Ropes remain connected to ropes, bars, panes and walls. Identify custom ropes by behavior
    // because their Bukkit material is only an appearance carrier.
    private boolean tieToRopeAndWalls(Block neighbor) {
        if (CustomBlockUtils.hasBehavior(neighbor, RopeBlockBehavior.class)) {
            return true;
        }
        if (!connectorBlocks.isEmpty()) {
            return connectorBlocks.contains(neighbor);
        }
        Material material = neighbor.getType();
        return PANE_BLOCKS.contains(material) || Tag.WALLS.isTagged(material);
    }

    // The wider tie only a fresh placement against a horizontal face can make: anything that is not on the
    // exception list and turns a full solid face towards the rope.
    private boolean tieToAnythingValid(Block neighbor, BlockFace faceTowardsRope) {
        if (!isExceptionForConnection(neighbor)
                && neighbor.getBlockData().isFaceSturdy(faceTowardsRope, BlockSupport.FULL)) {
            return true;
        }
        return tieToRopeAndWalls(neighbor);
    }

    private boolean isExceptionForConnection(Block neighbor) {
        Material material = neighbor.getType();
        if (exceptionBlocks != null) {
            return exceptionBlocks.contains(neighbor);
        }
        return CONNECTION_EXCEPTIONS.contains(material)
                || Tag.LEAVES.isTagged(material)
                || Tag.SHULKER_BOXES.isTagged(material);
    }

    private boolean connectsOnPlacement(World world, BlockPos pos, BlockFace direction, boolean horizontalPlacement) {
        Block neighbor = world.getBlockAt(
                pos.x() + direction.getModX(),
                pos.y() + direction.getModY(),
                pos.z() + direction.getModZ()
        );
        return switch (placementMode) {
            case RESTRICTED -> tieToRopeAndWalls(neighbor);
            case SOLID_FACE -> tieToAnythingValid(neighbor, direction.getOppositeFace());
            case VANILLA -> horizontalPlacement
                    ? tieToAnythingValid(neighbor, direction.getOppositeFace())
                    : tieToRopeAndWalls(neighbor);
        };
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

    // Programmatic placement, including downward reeling and harvested tomato supports,
    // uses the rope/pane/wall connection check on every side.
    public static ImmutableBlockState computeConnectionState(ImmutableBlockState state, World world, BlockPos pos) {
        RopeBlockBehavior behavior = CustomBlockUtils.getBehavior(state, RopeBlockBehavior.class);
        if (behavior == null) {
            return state;
        }
        return behavior.computeConnectionStateInternal(state, world, pos);
    }

    private ImmutableBlockState computeConnectionStateInternal(ImmutableBlockState state, World world, BlockPos pos) {
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
            result = result.with(property, connectsToNeighbor(neighbor, face.getOppositeFace()));
        }
        return result;
    }

    private boolean connectsToNeighbor(Block neighbor, BlockFace faceTowardsRope) {
        return connectionMode == ConnectionMode.SOLID_FACE
                ? tieToAnythingValid(neighbor, faceTowardsRope)
                : tieToRopeAndWalls(neighbor);
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

        for (BlockFace face : HORIZONTAL_FACES) {
            Block neighbor = world.getBlockAt(
                    pos.x() + face.getModX(),
                    pos.y() + face.getModY(),
                    pos.z() + face.getModZ()
            );
            // Neighbours on a chunk border are read and rewritten only while their chunk is resident: the CE
            // lookup would otherwise load it and the place below would cross into another region.
            ImmutableBlockState state = CustomBlockUtils.getStateIfResident(neighbor);
            if (state == null || state.isEmpty() || !CustomBlockUtils.hasBehavior(state, RopeBlockBehavior.class)) {
                continue;
            }

            RopeBlockBehavior behavior = CustomBlockUtils.getBehavior(state, RopeBlockBehavior.class);
            if (behavior == null) {
                continue;
            }

            Property<Boolean> property = connectionProperty(state, face.getOppositeFace());
            if (property == null) {
                continue;
            }

            ImmutableBlockState updated = state.with(property,
                    behavior.connectsToNeighbor(changed, face.getOppositeFace()));
            // with returns the same state when the value is unchanged, so an unaffected rope is left alone
            // instead of being rewritten and resent.
            if (updated == state) {
                continue;
            }

            CraftEngineBlocks.place(neighbor.getLocation(), updated, false);
        }
    }
}
