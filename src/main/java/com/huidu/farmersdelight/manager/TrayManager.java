package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.block.behavior.SkilletBlockBehavior;
import com.huidu.farmersdelight.config.HeatSourceConfig;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;

public class TrayManager {

    private final FarmersDelightPlugin plugin;
    private boolean enabled;
    private boolean requireNonFullSupport;

    public TrayManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    private void loadConfig() {
        ConfigurationSection config = plugin.getFirstConfigSection("tray", "cooking-pot.tray");
        if (config == null) {
            config = new org.bukkit.configuration.MemoryConfiguration();
        }
        enabled = config.getBoolean("enabled", true);
        requireNonFullSupport = config.getBoolean("require-non-full-support", true);
    }

    // Public API retained for backward compatibility

    public void checkAndPlaceTray(World world, BlockPos potPos) {
        if (!enabled || world == null || potPos == null) return;
        if (!isPotOrSkilletAt(world, potPos)) {
            removeTrayIfAutoPlaced(world, potPos);
            return;
        }

        boolean isPot = CookingPotBlockBehavior.isCookingPotBlock(world, new BlockPosKey(potPos));
        boolean wantTray = shouldHaveTray(world, potPos);

        if (isPot) {
            String current = getSupportProperty(world, potPos);
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
        } else {
            // Skillet support is represented by a boolean property.
            Boolean current = getSupportBoolean(world, potPos);
            if (wantTray != Boolean.TRUE.equals(current)) {
                setSupportBoolean(world, potPos, wantTray);
            }
        }
    }

    public void checkAndPlaceTray(Location location) {
        if (location == null || location.getWorld() == null) return;
        checkAndPlaceTray(location.getWorld(),
                new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ()));
    }

    public void removeTrayIfAutoPlaced(World world, BlockPos potPos) {
        if (!enabled || world == null || potPos == null) return;
        if (CookingPotBlockBehavior.isCookingPotBlock(world, new BlockPosKey(potPos))) {
            if ("tray".equals(getSupportProperty(world, potPos))) {
                setSupportProperty(world, potPos, "none");
            }
        } else if (isSkilletAt(world, potPos)) {
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

    public void syncAroundSupportChange(Location supportLocation) {
        if (!enabled || supportLocation == null || supportLocation.getWorld() == null) return;

        SkilletManager skilletManager = plugin.getSkilletManager();
        boolean anySkillets = skilletManager != null && skilletManager.hasTrackedSkillets();
        if (!CookingPotBlockBehavior.hasAnyBlockEntities() && !anySkillets) return;

        Location above1 = supportLocation.clone().add(0, 1, 0);
        Location above2 = supportLocation.clone().add(0, 2, 0);
        plugin.scheduler().runLaterAt(above1, () -> {
            checkAndPlaceTray(above1);
            checkAndPlaceTray(above2);
        }, 1L);
    }

    public boolean shouldHaveTray(World world, BlockPos potPos) {
        HeatSourceConfig heatConfig = plugin.getHeatSourceConfig();
        if (heatConfig == null) return false;

        // Handles take precedence over trays.
        HandleManager hm = plugin.getHandleManager();
        if (hm != null && hm.hasHandle(world, potPos)) return false;

        Block blockBelow = world.getBlockAt(potPos.x(), potPos.y() - 1, potPos.z());
        if (heatConfig.isHeatSource(blockBelow)) {
            return isValidTraySupport(blockBelow);
        }
        if (heatConfig.isConductor(blockBelow)) {
            Block blockTwoBelow = world.getBlockAt(potPos.x(), potPos.y() - 2, potPos.z());
            return heatConfig.isHeatSource(blockTwoBelow) && isValidTraySupport(blockBelow);
        }
        return false;
    }

    public void reload() {
        loadConfig();
    }

    public void stop() {}

    public void cleanupAll() {
        // Trays are block states now, so there are no entities to clean up.
    }

    public void cleanupWorld(java.util.UUID worldId) {
        // Trays are block states now, so there are no entities to clean up.
    }

    // Compatibility no-ops for the legacy furniture-based implementation
    @SuppressWarnings("unused")
    public boolean isAutoPlacedTray(Object furniture) { return false; }

    @SuppressWarnings("unused")
    public void markManualTrayFurniture(Object furniture) {}

    public void cleanupLegacyFurnitureEntities() {
        if (!enabled) return;
        // Known legacy furniture IDs.
        Key trayKey = Key.of("farmersdelight:tray");
        Key handleKey = Key.of("farmersdelight:cooking_pot_handle");
        // Legacy persistent-data marker keys.
        NamespacedKey trayMarker = new NamespacedKey(plugin, "auto_tray_marker");
        NamespacedKey handleMarker = new NamespacedKey(plugin, "auto_pot_handle");

        int removed = 0;
        for (World world : org.bukkit.Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                for (Entity entity : chunk.getEntities()) {
                    if (!(entity instanceof ItemDisplay)) continue;
                    boolean isOldTray = isLegacyFurniture(entity, trayKey, trayMarker,
                            "farmersdelight:auto_tray:", "farmersdelight:manual_tray");
                    boolean isOldHandle = isLegacyFurniture(entity, handleKey, handleMarker, null, null);
                    if (isOldTray || isOldHandle) {
                        try {
                            CraftEngineFurniture.remove(entity, false, false);
                        } catch (Exception ignored) {
                            entity.remove();
                        }
                        removed++;
                    }
                }
            }
        }
        if (removed > 0) {
            plugin.getLogger().info("Cleaned up " + removed + " legacy tray/handle furniture entities.");
        }
    }

    private boolean isLegacyFurniture(Entity entity, Key furnitureKey, NamespacedKey markerKey,
                                       String scoreboardPrefix, String manualTag) {
        // Preserve trays that players placed manually.
        if (manualTag != null && entity.getScoreboardTags().contains(manualTag)) return false;
        // Check the persistent-data marker.
        if (entity.getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE)) return true;
        // Check legacy scoreboard tags.
        if (scoreboardPrefix != null) {
            for (String tag : entity.getScoreboardTags()) {
                if (tag.startsWith(scoreboardPrefix)) return true;
            }
        }
        // Check the CraftEngine furniture ID.
        BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
        return furniture != null && furniture.id().equals(furnitureKey);
    }

    // Internal helpers

    private boolean isPotOrSkilletAt(World world, BlockPos pos) {
        return CookingPotBlockBehavior.isCookingPotBlock(world, new BlockPosKey(pos))
                || isSkilletAt(world, pos);
    }

    private boolean isSkilletAt(World world, BlockPos pos) {
        Location loc = new Location(world, pos.x(), pos.y(), pos.z());
        return CustomBlockUtils.hasBehavior(loc, SkilletBlockBehavior.class)
                || CustomBlockUtils.hasId(loc, Constants.BLOCK_SKILLET);
    }

    private boolean isValidTraySupport(Block block) {
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
