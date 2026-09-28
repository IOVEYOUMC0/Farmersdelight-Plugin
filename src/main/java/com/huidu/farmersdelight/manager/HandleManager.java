package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.SoundUtils;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

public final class HandleManager {

    private final FarmersDelightPlugin plugin;

    public HandleManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean hasHandle(World world, BlockPos potPos) {
        if (world == null || potPos == null) return false;
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(
                world.getBlockAt(potPos.x(), potPos.y(), potPos.z()));
        CookingPotBlockBehavior behavior = behaviorOf(state);
        return behavior != null && "handle".equals(supportValue(state, behavior));
    }

    public void toggleHandle(World world, BlockPos potPos, @Nullable Player player) {
        if (world == null || potPos == null) return;
        Block potBlock = world.getBlockAt(potPos.x(), potPos.y(), potPos.z());
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(potBlock);
        CookingPotBlockBehavior behavior = behaviorOf(state);
        if (behavior == null || behavior.getSupportProperty() == null || !behavior.isSupportDisplayEnabled()) return;

        // The state read for the behaviour also answers the current value, so the check does not need a
        // second CraftEngine lookup of the same block.
        boolean had = "handle".equals(supportValue(state, behavior));
        if (had) {
            setSupportProperty(potBlock, state, behavior, "none");
            TrayManager trayManager = plugin.getTrayManager();
            if (trayManager != null) trayManager.checkAndPlaceTray(world, potPos);
        } else {
            TrayManager trayManager = plugin.getTrayManager();
            if (trayManager != null) trayManager.removeTrayIfAutoPlaced(world, potPos);
            // The tray pass can rewrite the block, so this write re-reads the state it builds on.
            setSupportProperty(potBlock, "handle");
        }
        if (player != null && behavior.getHandleToggleSoundVolume() > 0) {
            SoundUtils.play(player, player.getLocation(), behavior.getHandleToggleSound(), Sound.BLOCK_LANTERN_PLACE,
                    SoundCategory.BLOCKS, behavior.getHandleToggleSoundVolume(), behavior.getHandleToggleSoundPitch());
        }
    }

    public void removeHandle(World world, BlockPos potPos) {
        if (world == null || potPos == null) return;
        Block potBlock = world.getBlockAt(potPos.x(), potPos.y(), potPos.z());
        // Nothing between the read and the write touches the block, so one state serves all three steps.
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(potBlock);
        CookingPotBlockBehavior behavior = behaviorOf(state);
        if (behavior == null) return;
        if ("handle".equals(supportValue(state, behavior))) {
            setSupportProperty(potBlock, state, behavior, "none");
        }
    }

    // Internal helpers

    @Nullable
    private static CookingPotBlockBehavior behaviorOf(@Nullable ImmutableBlockState state) {
        if (state == null || state.isEmpty()) return null;
        return CustomBlockUtils.getBehavior(state, CookingPotBlockBehavior.class);
    }

    @Nullable
    private static String supportValue(ImmutableBlockState state, CookingPotBlockBehavior behavior) {
        if (behavior.getSupportProperty() == null) return null;
        return state.getNullable(behavior.getSupportProperty());
    }

    private static void setSupportProperty(Block block, ImmutableBlockState state,
                                           CookingPotBlockBehavior behavior, String value) {
        if (behavior.getSupportProperty() == null) return;
        CraftEngineBlocks.place(block.getLocation(), state.with(behavior.getSupportProperty(), value), false);
    }

    /** Write that re-reads the block first, for callers that may have changed it since their own read. */
    private static void setSupportProperty(Block block, String value) {
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
        CookingPotBlockBehavior behavior = behaviorOf(state);
        if (behavior == null) return;
        setSupportProperty(block, state, behavior, value);
    }
}
