package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.ProtectionCompat;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehavior;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

public class RopeBlockBehavior extends BlockBehavior {

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

    private static final String PROP_NORTH = "north";
    private static final String PROP_SOUTH = "south";
    private static final String PROP_EAST = "east";
    private static final String PROP_WEST = "west";

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

    @SuppressWarnings("unchecked")
    public static final BlockBehaviorFactory<RopeBlockBehavior> FACTORY = new BlockBehaviorFactory<>() {
        @Override
        public RopeBlockBehavior create(BlockDefinition block, net.momirealms.craftengine.core.plugin.config.ConfigSection section) {
            Map<String, Object> arguments = section != null ? section.values() : Map.of();
            return new RopeBlockBehavior(
                    block,
                    (Property<Boolean>) block.getProperty(PROP_NORTH),
                    (Property<Boolean>) block.getProperty(PROP_SOUTH),
                    (Property<Boolean>) block.getProperty(PROP_EAST),
                    (Property<Boolean>) block.getProperty(PROP_WEST)
            );
        }
    };

    @Override
    public ImmutableBlockState updateStateForPlacement(BlockPlaceContext context, ImmutableBlockState state) {
        BlockPos pos = context.getClickedPos();
        World world = (World) context.getLevel().platformWorld();

        ImmutableBlockState result = state;
        if (northProperty != null) result = result.with(northProperty, isRopeAt(world, pos, BlockFace.NORTH));
        if (southProperty != null) result = result.with(southProperty, isRopeAt(world, pos, BlockFace.SOUTH));
        if (eastProperty != null) result = result.with(eastProperty, isRopeAt(world, pos, BlockFace.EAST));
        if (westProperty != null) result = result.with(westProperty, isRopeAt(world, pos, BlockFace.WEST));

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
        if (hand == null || hand.getType().isAir()) return InteractionResult.PASS;

        String handItemId = ItemUtils.getCustomItemId(hand);
        if (handItemId == null || !state.owner().value().id().toString().equals(handItemId)) {
            return InteractionResult.PASS;
        }

        BlockPos pos = context.getClickedPos();
        World world = (World) context.getLevel().platformWorld();
        Direction clickedFace = context.getClickedFace();

        Direction extendDir;
        if (bukkitPlayer.isSneaking()) {
            extendDir = clickedFace;
        } else {
            extendDir = Direction.DOWN;
        }

        int cx = pos.x() + extendDir.stepX();
        int cy = pos.y() + extendDir.stepY();
        int cz = pos.z() + extendDir.stepZ();

        while (cy >= world.getMinHeight()) {
            Block target = world.getBlockAt(cx, cy, cz);
            if (!CustomBlockUtils.hasBehavior(target, RopeBlockBehavior.class)) {
                if (!target.getType().isAir() && !target.isLiquid()) return InteractionResult.PASS;

                BlockDefinition ropeBlock = CraftEngineBlocks.byId(state.owner().value().id());
                if (ropeBlock == null) return InteractionResult.PASS;

                BlockPos bp = new BlockPos(cx, cy, cz);
                ImmutableBlockState placementState = computeConnectionState(ropeBlock.defaultState(), world, bp);

                // R-SEC-001: this path DENYs the vanilla interaction and places the block itself via
                // CraftEngineBlocks.place, so vanilla's own WorldGuard build check never fires — gate here.
                if (!ProtectionCompat.canBuild(bukkitPlayer, target, ProtectionCompat.Feature.ROPE)) {
                    return InteractionResult.PASS;
                }

                Location placeLoc = new Location(world, cx + 0.5, cy, cz + 0.5);
                if (!CraftEngineBlocks.place(placeLoc, placementState, true)) return InteractionResult.PASS;

                world.playSound(placeLoc, Sound.BLOCK_WOOL_PLACE, 1.0f, 1.0f);

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
            if (extendDir != Direction.DOWN) {
                return InteractionResult.PASS;
            }
            cy--;
        }

        return InteractionResult.PASS;
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
        for (int i = 1, y = pos.y() + 1; i <= 24 && y <= world.getMaxHeight(); i++, y++) {
            Block above = world.getBlockAt(x, y, z);
            if (above.getType() == Material.BELL) {
                ringBell(above, bukkitPlayer);
                return InteractionResult.SUCCESS;
            }
            if (!CustomBlockUtils.hasBehavior(above, RopeBlockBehavior.class)) {
                return InteractionResult.PASS;
            }
        }
        return InteractionResult.PASS;
    }

    /**
     * Rings a bell as if a player pulled the rope, matching vanilla RopeBlock: the bell swings from its own
     * facing rotated clockwise. Scheduled on the bell's location so the tile-entity read and ring run on the
     * region thread that owns the bell.
     */
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

    private static boolean isRopeAt(World world, BlockPos pos, BlockFace direction) {
        Block neighbor = world.getBlockAt(
                pos.x() + direction.getModX(),
                pos.y() + direction.getModY(),
                pos.z() + direction.getModZ()
        );
        return CustomBlockUtils.hasBehavior(neighbor, RopeBlockBehavior.class);
    }

    public static ImmutableBlockState computeConnectionState(ImmutableBlockState state, World world, BlockPos pos) {
        BlockDefinition owner = state.owner().value();
        ImmutableBlockState result = state;

        Property<?> nProp = owner.getProperty(PROP_NORTH);
        Property<?> sProp = owner.getProperty(PROP_SOUTH);
        Property<?> eProp = owner.getProperty(PROP_EAST);
        Property<?> wProp = owner.getProperty(PROP_WEST);

        if (nProp instanceof Property<?>) {
            @SuppressWarnings("unchecked")
            Property<Boolean> np = (Property<Boolean>) nProp;
            result = result.with(np, isRopeAt(world, pos, BlockFace.NORTH));
        }
        if (sProp instanceof Property<?>) {
            @SuppressWarnings("unchecked")
            Property<Boolean> sp = (Property<Boolean>) sProp;
            result = result.with(sp, isRopeAt(world, pos, BlockFace.SOUTH));
        }
        if (eProp instanceof Property<?>) {
            @SuppressWarnings("unchecked")
            Property<Boolean> ep = (Property<Boolean>) eProp;
            result = result.with(ep, isRopeAt(world, pos, BlockFace.EAST));
        }
        if (wProp instanceof Property<?>) {
            @SuppressWarnings("unchecked")
            Property<Boolean> wp = (Property<Boolean>) wProp;
            result = result.with(wp, isRopeAt(world, pos, BlockFace.WEST));
        }
        return result;
    }

    public static void refreshAdjacentRopes(World world, BlockPos pos) {
        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block neighbor = world.getBlockAt(
                    pos.x() + face.getModX(),
                    pos.y() + face.getModY(),
                    pos.z() + face.getModZ()
            );
            ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(neighbor);
            if (state == null || state.isEmpty() || !CustomBlockUtils.hasBehavior(state, RopeBlockBehavior.class)) {
                continue;
            }

            BlockPos neighborPos = new BlockPos(
                    pos.x() + face.getModX(),
                    pos.y() + face.getModY(),
                    pos.z() + face.getModZ()
            );

            Property<?> nProp = state.owner().value().getProperty(PROP_NORTH);
            Property<?> sProp = state.owner().value().getProperty(PROP_SOUTH);
            Property<?> eProp = state.owner().value().getProperty(PROP_EAST);
            Property<?> wProp = state.owner().value().getProperty(PROP_WEST);

            boolean northConn = isRopeAt(world, neighborPos, BlockFace.NORTH);
            boolean southConn = isRopeAt(world, neighborPos, BlockFace.SOUTH);
            boolean eastConn = isRopeAt(world, neighborPos, BlockFace.EAST);
            boolean westConn = isRopeAt(world, neighborPos, BlockFace.WEST);

            ImmutableBlockState updated = state;
            if (nProp instanceof Property<?>) {
                @SuppressWarnings("unchecked")
                Property<Boolean> northP = (Property<Boolean>) nProp;
                updated = updated.with(northP, northConn);
            }
            if (sProp instanceof Property<?>) {
                @SuppressWarnings("unchecked")
                Property<Boolean> southP = (Property<Boolean>) sProp;
                updated = updated.with(southP, southConn);
            }
            if (eProp instanceof Property<?>) {
                @SuppressWarnings("unchecked")
                Property<Boolean> eastP = (Property<Boolean>) eProp;
                updated = updated.with(eastP, eastConn);
            }
            if (wProp instanceof Property<?>) {
                @SuppressWarnings("unchecked")
                Property<Boolean> westP = (Property<Boolean>) wProp;
                updated = updated.with(westP, westConn);
            }

            CraftEngineBlocks.place(neighbor.getLocation(), updated, false);
        }
    }
}

