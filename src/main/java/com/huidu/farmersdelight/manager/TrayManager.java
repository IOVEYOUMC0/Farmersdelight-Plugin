package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.block.behavior.SkilletBlockBehavior;
import com.huidu.farmersdelight.config.HeatSourceConfig;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;

public class TrayManager {

    private final FarmersDelightPlugin plugin;

    public TrayManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    // Public API retained for backward compatibility

    public void checkAndPlaceTray(World world, BlockPos potPos) {
        if (world == null || potPos == null) return;
        CookingPotBlockBehavior cookingPot = getCookingPotBehavior(world, potPos);
        if (cookingPot != null) {
            String current = getSupportProperty(world, potPos);
            if (!cookingPot.isSupportDisplayEnabled()) {
                if ("tray".equals(current)) {
                    setSupportProperty(world, potPos, "none");
                }
                return;
            }
            boolean wantTray = shouldHaveTray(world, potPos, cookingPot.requiresNonFullSupport());
            if (wantTray) {
                if (!"tray".equals(current)) {
                    setSupportProperty(world, potPos, "tray");
                }
            } else {
                // Clear only an existing tray so a handle state is never overwritten.
                if ("tray".equals(current)) {
                    setSupportProperty(world, potPos, "none");
                }
            }
            return;
        }

        SkilletBlockBehavior skillet = getSkilletBehavior(world, potPos);
        if (skillet == null) {
            return;
        }
        Boolean current = getSupportBoolean(world, potPos);
        boolean wantTray = skillet.isSupportDisplayEnabled()
                && shouldHaveTray(world, potPos, skillet.requiresNonFullSupport());
        if (wantTray != Boolean.TRUE.equals(current)) {
            setSupportBoolean(world, potPos, wantTray);
        }
    }

    public void checkAndPlaceTray(Location location) {
        if (location == null || location.getWorld() == null) return;
        checkAndPlaceTray(location.getWorld(),
                new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ()));
    }

    public void removeTrayIfAutoPlaced(World world, BlockPos potPos) {
        if (world == null || potPos == null) return;
        if (getCookingPotBehavior(world, potPos) != null) {
            if ("tray".equals(getSupportProperty(world, potPos))) {
                setSupportProperty(world, potPos, "none");
            }
        } else if (getSkilletBehavior(world, potPos) != null) {
            if (Boolean.TRUE.equals(getSupportBoolean(world, potPos))) {
                setSupportBoolean(world, potPos, false);
            }
        }
    }

    public void removeTrayIfAutoPlaced(Location location) {
        if (location == null || location.getWorld() == null) return;
        removeTrayIfAutoPlaced(location.getWorld(),
                new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ()));
    }

    /**
     * Re-evaluates the trays above a block whose support changed. Runs for every block break and for every block
     * of an explosion, so the station above is checked inline first: a scheduled task that would find no station
     * is the expensive outcome, and a large explosion used to queue one task per broken block.
     */
    public void syncAroundSupportChange(Location supportLocation) {
        if (supportLocation == null || supportLocation.getWorld() == null) return;

        SkilletManager skilletManager = plugin.getSkilletManager();
        boolean anySkillets = skilletManager != null && skilletManager.hasTrackedSkillets();
        if (!CookingPotBlockBehavior.hasAnyBlockEntities() && !anySkillets) return;

        World world = supportLocation.getWorld();
        int x = supportLocation.getBlockX();
        int y = supportLocation.getBlockY();
        int z = supportLocation.getBlockZ();
        BlockPos aboveOne = new BlockPos(x, y + 1, z);
        BlockPos aboveTwo = new BlockPos(x, y + 2, z);
        // checkAndPlaceTray returns immediately when neither a pot nor a skillet stands there, so this
        // predicate only skips work that would have been discarded anyway.
        if (!hasStationAt(world, aboveOne) && !hasStationAt(world, aboveTwo)) {
            return;
        }

        Location above1 = new Location(world, x, y + 1, z);
        Location above2 = new Location(world, x, y + 2, z);
        plugin.scheduler().runLaterAt(above1, () -> {
            // A queued support update must not load a chunk that was unloaded in the meantime.
            if (!world.isChunkLoaded(x >> 4, z >> 4)) return;
            if (y + 1 >= world.getMinHeight() && y + 1 < world.getMaxHeight()) checkAndPlaceTray(above1);
            if (y + 2 >= world.getMinHeight() && y + 2 < world.getMaxHeight()) checkAndPlaceTray(above2);
        }, 1L);
    }

    /** True when a cooking pot or skillet stands at this position; the tray rules only apply there. */
    private boolean hasStationAt(World world, BlockPos pos) {
        if (pos.y() < world.getMinHeight() || pos.y() >= world.getMaxHeight()) {
            return false;
        }
        if (!plugin.scheduler().isOwnedByCurrentRegion(new Location(world, pos.x(), pos.y(), pos.z()))
                || !world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) {
            return false;
        }
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(
                world.getBlockAt(pos.x(), pos.y(), pos.z()));
        if (state == null || state.isEmpty()) {
            return false;
        }
        return CustomBlockUtils.getBehavior(state, CookingPotBlockBehavior.class) != null
                || CustomBlockUtils.getBehavior(state, SkilletBlockBehavior.class) != null;
    }

    public boolean shouldHaveTray(World world, BlockPos potPos) {
        CookingPotBlockBehavior cookingPot = getCookingPotBehavior(world, potPos);
        if (cookingPot != null) {
            return shouldHaveTray(world, potPos, cookingPot.requiresNonFullSupport());
        }
        SkilletBlockBehavior skillet = getSkilletBehavior(world, potPos);
        return skillet != null && shouldHaveTray(world, potPos, skillet.requiresNonFullSupport());
    }

    private boolean shouldHaveTray(World world, BlockPos potPos, boolean requireNonFullSupport) {
        HeatSourceConfig heatConfig = plugin.getHeatSourceConfig();
        if (heatConfig == null) return false;

        // Handles take precedence over trays.
        HandleManager hm = plugin.getHandleManager();
        if (hm != null && hm.hasHandle(world, potPos)) return false;

        Block blockBelow = world.getBlockAt(potPos.x(), potPos.y() - 1, potPos.z());
        if (heatConfig.isHeatSource(blockBelow)) {
            return isValidTraySupport(blockBelow, requireNonFullSupport);
        }
        if (heatConfig.isConductor(blockBelow)) {
            Block blockTwoBelow = world.getBlockAt(potPos.x(), potPos.y() - 2, potPos.z());
            return heatConfig.isHeatSource(blockTwoBelow) && isValidTraySupport(blockBelow, requireNonFullSupport);
        }
        return false;
    }

    // Internal helpers

    private CookingPotBlockBehavior getCookingPotBehavior(World world, BlockPos pos) {
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(
                world.getBlockAt(pos.x(), pos.y(), pos.z()));
        return state == null || state.isEmpty() ? null
                : CustomBlockUtils.getBehavior(state, CookingPotBlockBehavior.class);
    }

    private SkilletBlockBehavior getSkilletBehavior(World world, BlockPos pos) {
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(
                world.getBlockAt(pos.x(), pos.y(), pos.z()));
        return state == null || state.isEmpty() ? null
                : CustomBlockUtils.getBehavior(state, SkilletBlockBehavior.class);
    }

    private boolean isValidTraySupport(Block block, boolean requireNonFullSupport) {
        return !requireNonFullSupport || isNonFullSupport(block);
    }

    private boolean isNonFullSupport(Block block) {
        if (block == null) return false;
        Material type = block.getType();
        if (type == Material.HOPPER) return false;
        if (!type.isOccluding()) return true;
        BoundingBox box = block.getBoundingBox();
        return box.getMaxY() - box.getMinY() < 0.99D;
    }

    // Read and write typed behavior properties without raw types or string lookups.

    private String getSupportProperty(World world, BlockPos pos) {
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(
                world.getBlockAt(pos.x(), pos.y(), pos.z()));
        if (state == null || state.isEmpty()) return null;
        CookingPotBlockBehavior behavior = CustomBlockUtils.getBehavior(state, CookingPotBlockBehavior.class);
        if (behavior == null || behavior.getSupportProperty() == null) return null;
        return state.getNullable(behavior.getSupportProperty());
    }

    private Boolean getSupportBoolean(World world, BlockPos pos) {
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(
                world.getBlockAt(pos.x(), pos.y(), pos.z()));
        if (state == null || state.isEmpty()) return null;
        SkilletBlockBehavior behavior = CustomBlockUtils.getBehavior(state, SkilletBlockBehavior.class);
        if (behavior == null || behavior.getSupportProperty() == null) return null;
        return state.getNullable(behavior.getSupportProperty());
    }

    private void setSupportProperty(World world, BlockPos pos, String value) {
        Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
        if (state == null || state.isEmpty()) return;
        CookingPotBlockBehavior behavior = CustomBlockUtils.getBehavior(state, CookingPotBlockBehavior.class);
        if (behavior == null || behavior.getSupportProperty() == null) return;
        ImmutableBlockState next = state.with(behavior.getSupportProperty(), value);
        CraftEngineBlocks.place(block.getLocation(), next, false);
    }

    private void setSupportBoolean(World world, BlockPos pos, boolean value) {
        Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
        ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
        if (state == null || state.isEmpty()) return;
        SkilletBlockBehavior behavior = CustomBlockUtils.getBehavior(state, SkilletBlockBehavior.class);
        if (behavior == null || behavior.getSupportProperty() == null) return;
        ImmutableBlockState next = state.with(behavior.getSupportProperty(), value);
        CraftEngineBlocks.place(block.getLocation(), next, true);
    }
}
