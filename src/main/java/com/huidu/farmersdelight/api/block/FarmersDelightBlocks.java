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

/**
 * Read-only queries against FarmersDelight blocks, for third-party plugins that need to know what a
 * block is and what a station currently holds. Every parameter and return type is a Bukkit or java
 * type (plus the snapshot records in this package), so callers need no CraftEngine dependency.
 *
 * Never identify a station by Material
 * FarmersDelight stations are CraftEngine custom blocks. CraftEngine reports a configurable disguise
 * material through Bukkit — by default the same one for every custom block — so
 * block.getType() == Material.X identifies nothing and silently changes when the server owner
 * edits CraftEngine's disguise setting. Always go through isStation / stationOf /
 * stationIdOf here, which resolve the real custom block state.
 *
 * Getting a block id right
 * The obvious-looking route from a CraftEngine block state to its id is a trap: the state's owner
 * exposes an Optional wrapper around a resource key, and calling toString on that wrapper yields the
 * wrapper's own text form (an Optional rendering of a resource key), never the plain
 * "namespace:path" id. Comparing that against "farmersdelight:cooking_pot" is always false, so the
 * guarded code becomes dead and nothing reports an error. The id must be taken from the resource
 * key's location. blockIdOf and stationIdOf already do that, so callers should use
 * them rather than walking the CraftEngine state themselves.
 *
 * Threading (Folia / Luminol)
 * Every method here reads world state, so the caller must already be on the region thread that owns
 * block — inside a listener for an event at that block, or inside a task scheduled with
 * FarmersDelightApi.get().runAtLocation(block.getLocation(), ...). Calling from another
 * region's thread, from the global region, or from an async task reaches Paper's thread check in
 * CraftWorld and throws (the moonrise tick-thread assertion), which surfaces as an exception
 * inside the caller's listener, not as a null return. On a single-threaded Paper server there is one
 * region, so any main-thread call is fine — which is exactly why this is easy to miss until someone
 * runs the plugin on Folia. These methods never schedule for you: a snapshot must be returned
 * synchronously, and hopping regions would return data from a different tick.
 *
 * A snapshot is a copy taken at the instant of the call. The station keeps ticking afterwards, so
 * treat the values as a reading, not as a handle.
 */
@ApiStatus.Experimental
@ApiStatus.NonExtendable
public final class FarmersDelightBlocks {

    private FarmersDelightBlocks() {
    }

    /**
     * The CraftEngine block id of block (e.g. "farmersdelight:cooking_pot", or an addon's
     * "brewinandchewin:keg"), or null when the block is not a CraftEngine custom block at all. Works
     * for any custom block, not only FarmersDelight's.
     */
    public static String blockIdOf(Block block) {
        return CustomBlockUtils.getId(state(block));
    }

    /**
     * The block id of block when it is one of the four FarmersDelight stations, otherwise
     * null. The returned id is the id of the block actually placed in the world, which is normally
     * "farmersdelight:cooking_pot" / "farmersdelight:cutting_board" / "farmersdelight:skillet" /
     * "farmersdelight:stove" but differs when the station behavior is attached to a re-skinned block.
     * Use stationOf when you want to branch on which station it is.
     */
    public static String stationIdOf(Block block) {
        ImmutableBlockState state = state(block);
        return stationOf(state) == null ? null : CustomBlockUtils.getId(state);
    }

    /** True when block is any of the four FarmersDelight stations. */
    public static boolean isStation(Block block) {
        return stationOf(state(block)) != null;
    }

    /**
     * Which FarmersDelight station block is, or null when it is not one. Detection is by the
     * CraftEngine block behavior attached to the block, so a re-skinned or addon-provided block that
     * reuses a station behavior is still recognised.
     */
    public static FarmersDelightStation stationOf(Block block) {
        return stationOf(state(block));
    }

    /**
     * Snapshot of the cooking pot at block, or null when the block is not a cooking pot or
     * has no block entity loaded (an unloaded chunk, or a pot placed this tick that has not been
     * initialised). See the class javadoc for the region-thread requirement.
     */
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

    /**
     * Snapshot of the cutting board at block, or null when the block is not a cutting board.
     * An empty board still returns a snapshot (with a null stored item); only a non-board or a board
     * with no loaded block entity returns null. See the class javadoc for the region-thread requirement.
     */
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

    /**
     * Snapshot of the skillet at block, or null when the block is not a skillet or is not
     * tracked (nothing has ever been placed in or on it). See the class javadoc for the region-thread
     * requirement.
     */
    public static SkilletSnapshot skillet(Block block) {
        FarmersDelightPlugin plugin = plugin();
        if (plugin == null || stationOf(state(block)) != FarmersDelightStation.SKILLET
                || plugin.getSkilletManager() == null) {
            return null;
        }
        return plugin.getSkilletManager().snapshot(block.getLocation());
    }

    /**
     * Snapshot of the stove at block, or null when the block is not a stove or is not tracked
     * (no food has ever been put on it). See the class javadoc for the region-thread requirement.
     */
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
