package com.huidu.farmersdelight.api.block;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockEntity;
import com.huidu.farmersdelight.block.behavior.CuttingBoardBlockBehavior;
import com.huidu.farmersdelight.block.behavior.CuttingBoardBlockEntity;
import com.huidu.farmersdelight.block.behavior.SkilletBlockBehavior;
import com.huidu.farmersdelight.block.behavior.StoveCookingBlockBehavior;
import com.huidu.farmersdelight.recipe.CookingPotRecipe;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Experimental
@ApiStatus.NonExtendable
public final class FarmersDelightBlocks {

    private FarmersDelightBlocks() {
    }

    public static String blockIdOf(Block block) {
        return CustomBlockUtils.getId(state(block));
    }

    public static String stationIdOf(Block block) {
        ImmutableBlockState state = state(block);
        return stationOf(state) == null ? null : CustomBlockUtils.getId(state);
    }

    public static boolean isStation(Block block) {
        return stationOf(state(block)) != null;
    }

    public static FarmersDelightStation stationOf(Block block) {
        return stationOf(state(block));
    }

    public static CookingPotSnapshot cookingPot(Block block) {
        if (!available() || stationOf(state(block)) != FarmersDelightStation.COOKING_POT) {
            return null;
        }
        CookingPotBlockEntity entity = CookingPotBlockBehavior.getBlockEntity(block.getLocation());
        if (entity == null) {
            return null;
        }
        CookingPotRecipe recipe = entity.getCurrentRecipe();
        return new CookingPotSnapshot(
                blockLocation(block),
                entity.getIngredientSlots(),
                entity.getContainerItem(),
                entity.getMealDisplayItem(),
                entity.getPendingOutputItem(),
                recipe == null ? null : recipe.getId(),
                entity.getCookingProgress(),
                entity.getCookingDuration(),
                entity.getRemainingTime(),
                entity.hasHeatSource());
    }

    public static CuttingBoardSnapshot cuttingBoard(Block block) {
        if (!available() || stationOf(state(block)) != FarmersDelightStation.CUTTING_BOARD) {
            return null;
        }
        World world = block.getWorld();
        CuttingBoardBlockEntity entity =
                CuttingBoardBlockBehavior.getBlockEntity(world, new BlockPosKey(block.getX(), block.getY(), block.getZ()));
        if (entity == null) {
            return null;
        }
        return new CuttingBoardSnapshot(blockLocation(block), entity.getStoredItem(), entity.isItemCarved());
    }

    public static SkilletSnapshot skillet(Block block) {
        FarmersDelightPlugin plugin = plugin();
        if (plugin == null || stationOf(state(block)) != FarmersDelightStation.SKILLET
                || plugin.getSkilletManager() == null) {
            return null;
        }
        return plugin.getSkilletManager().snapshot(block.getLocation());
    }

    public static StoveSnapshot stove(Block block) {
        FarmersDelightPlugin plugin = plugin();
        if (plugin == null || stationOf(state(block)) != FarmersDelightStation.STOVE
                || plugin.getStoveManager() == null) {
            return null;
        }
        return plugin.getStoveManager().snapshot(block.getLocation());
    }

    private static FarmersDelightStation stationOf(ImmutableBlockState state) {
        if (state == null || state.isEmpty()) {
            return null;
        }
        if (CustomBlockUtils.hasBehavior(state, CookingPotBlockBehavior.class)) {
            return FarmersDelightStation.COOKING_POT;
        }
        if (CustomBlockUtils.hasBehavior(state, CuttingBoardBlockBehavior.class)) {
            return FarmersDelightStation.CUTTING_BOARD;
        }
        if (CustomBlockUtils.hasBehavior(state, SkilletBlockBehavior.class)) {
            return FarmersDelightStation.SKILLET;
        }
        if (CustomBlockUtils.hasBehavior(state, StoveCookingBlockBehavior.class)) {
            return FarmersDelightStation.STOVE;
        }
        return null;
    }

    private static ImmutableBlockState state(Block block) {
        return block == null ? null : CustomBlockUtils.getState(block);
    }

    private static Location blockLocation(Block block) {
        return new Location(block.getWorld(), block.getX(), block.getY(), block.getZ());
    }

    private static FarmersDelightPlugin plugin() {
        return available() ? FarmersDelightPlugin.getInstance() : null;
    }

    private static boolean available() {
        return FarmersDelightPlugin.getInstance() != null && FarmersDelightPlugin.isEnabled0();
    }
}
