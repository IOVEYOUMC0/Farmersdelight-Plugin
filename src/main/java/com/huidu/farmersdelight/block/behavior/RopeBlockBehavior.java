package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ItemUtils;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.core.block.CustomBlock;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.behavior.BlockBehavior;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviorFactory;
import net.momirealms.craftengine.core.block.properties.Property;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.context.BlockPlaceContext;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.concurrent.Callable;

public class RopeBlockBehavior extends BlockBehavior {

    private static final String PROP_NORTH = "north";
    private static final String PROP_SOUTH = "south";
    private static final String PROP_EAST = "east";
    private static final String PROP_WEST = "west";

    private final Property<Boolean> northProperty;
    private final Property<Boolean> southProperty;
    private final Property<Boolean> eastProperty;
    private final Property<Boolean> westProperty;

    private RopeBlockBehavior(CustomBlock block,
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
        public RopeBlockBehavior create(CustomBlock block, Map<String, Object> arguments) {
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
    public void placeMultiState(Object thisBlock, Object[] args, Callable<Object> superMethod) throws Exception {
        superMethod.call();
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

                CustomBlock ropeBlock = CraftEngineBlocks.byId(state.owner().value().id());
                if (ropeBlock == null) return InteractionResult.PASS;

                BlockPos bp = new BlockPos(cx, cy, cz);
                ImmutableBlockState placementState = computeConnectionState(ropeBlock.defaultState(), world, bp);

                Location placeLoc = new Location(world, cx + 0.5, cy, cz + 0.5);
                if (!CraftEngineBlocks.place(placeLoc, placementState, true)) return InteractionResult.PASS;

                world.playSound(placeLoc, Sound.BLOCK_WOOL_PLACE, 1.0f, 1.0f);

                Bukkit.getScheduler().runTask(
                        Bukkit.getPluginManager().getPlugin("FarmersDelight"),
                        () -> refreshAdjacentRopes(world, bp));

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
        if (context.getPlayer() == null) return InteractionResult.PASS;

        Player bukkitPlayer = Bukkit.getPlayer(context.getPlayer().uuid());
        if (bukkitPlayer == null) return InteractionResult.PASS;
        if (!bukkitPlayer.isSneaking()) return InteractionResult.PASS;

        BlockPos pos = context.getClickedPos();
        World world = (World) context.getLevel().platformWorld();

        int bottomY = pos.y();
        int checkY = pos.y() - 1;
        while (checkY >= world.getMinHeight()) {
            Block below = world.getBlockAt(pos.x(), checkY, pos.z());
            if (CustomBlockUtils.hasBehavior(below, RopeBlockBehavior.class)) {
                bottomY = checkY;
                checkY--;
            } else {
                break;
            }
        }

        Block bottomBlock = world.getBlockAt(pos.x(), bottomY, pos.z());
        boolean isCreative = bukkitPlayer.getGameMode() == GameMode.CREATIVE;

        if (!isCreative) {
            ItemStack recovered = buildRopeItemStatic();
            if (recovered == null) return InteractionResult.PASS;
            if (!bukkitPlayer.getInventory().addItem(recovered).isEmpty()) {
                world.dropItemNaturally(bottomBlock.getLocation(), recovered);
            }
        }

        CraftEngineBlocks.remove(bottomBlock);
        world.playSound(bottomBlock.getLocation(), Sound.BLOCK_WOOL_BREAK, 1.0f, 1.0f);

        int removedY = bottomY;
        BlockPos bp = new BlockPos(pos.x(), removedY, pos.z());
        Bukkit.getScheduler().runTask(
                Bukkit.getPluginManager().getPlugin("FarmersDelight"),
                () -> refreshAdjacentRopes(world, bp));

        return InteractionResult.SUCCESS_AND_CANCEL;
    }

    public static ItemStack buildRopeItemStatic() {
        try {
            var item = BukkitCraftEngine.instance().itemManager()
                    .getCustomItem(Key.of("farmersdelight:rope"))
                    .orElse(null);
            if (item != null) return item.buildItemStack();
        } catch (Exception ignored) {
        }
        return null;
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
        CustomBlock owner = state.owner().value();
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
