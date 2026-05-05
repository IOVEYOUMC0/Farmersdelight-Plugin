package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.config.HeatSourceConfig;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TrayManager {

    private static final long SYNC_INTERVAL_TICKS = 20L;
    private static final Map<UUID, Map<BlockPos, BlockPos>> cookingPotTrays = new ConcurrentHashMap<>();

    private final FarmersDelightPlugin plugin;
    private final Map<BlockPos, BukkitFurniture> trayFurnitureCache = new ConcurrentHashMap<>();
    private String trayFurnitureId;
    private double xOffset;
    private double yOffset;
    private double zOffset;
    private boolean requireNonFullSupport;
    private NamespacedKey trayMarkerKey;
    private boolean enabled;
    private BukkitTask syncTask;

    public TrayManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        loadConfig();
        start();
    }

    @Nullable
    public static BlockPos getTrayPosForPot(World world, BlockPos potPos) {
        if (world == null) return null;
        Map<BlockPos, BlockPos> worldTrays = cookingPotTrays.get(world.getUID());
        if (worldTrays == null) return null;
        return worldTrays.get(potPos);
    }

    private void loadConfig() {
        ConfigurationSection config = plugin.getConfig().getConfigurationSection("tray");
        if (config == null) {
            config = plugin.getConfig().createSection("tray");
        }

        enabled = config.getBoolean("enabled", true);
        trayFurnitureId = config.getString("tray-block-id", "farmersdelight:tray");
        xOffset = config.getDouble("x-offset", 0.5D);
        yOffset = config.getDouble("y-offset", -1.0D);
        zOffset = config.getDouble("z-offset", 0.5D);
        requireNonFullSupport = config.getBoolean("require-non-full-support", true);
        String markerKey = config.getString("marker-key", "auto_tray_marker");
        trayMarkerKey = new NamespacedKey("farmersdelight", markerKey);
    }

    public void reload() {
        loadConfig();
        if (!enabled) {
            stop();
            removeAllTrays();
            return;
        }

        stop();
        removeAllTrays();
        trayFurnitureCache.clear();
        start();
        syncAllTrays();
    }

    private void start() {
        stop();
        syncTask = Bukkit.getScheduler().runTaskTimer(plugin, this::syncAllTrays, 1L, SYNC_INTERVAL_TICKS);
    }

    public void stop() {
        if (syncTask != null) {
            syncTask.cancel();
            syncTask = null;
        }
    }

    public void checkAndPlaceTray(World world, BlockPos potPos) {
        if (!enabled || world == null || potPos == null) return;

        if (!shouldHaveTray(world, potPos)) {
            return;
        }

        Location trayLoc = getTrayLocation(world, potPos);
        if (trayLoc.getY() < world.getMinHeight() || trayLoc.getY() > world.getMaxHeight()) {
            return;
        }

        BlockPos trayPos = new BlockPos(trayLoc.getBlockX(), trayLoc.getBlockY(), trayLoc.getBlockZ());
        BukkitFurniture cached = trayFurnitureCache.get(trayPos);
        if (cached != null && cached.bukkitEntity() != null && cached.bukkitEntity().isValid()) {
            return;
        }

        if (!canPlaceTrayAt(trayLoc.getBlock())) {
            return;
        }

        try {
            BukkitFurniture furniture = CraftEngineFurniture.place(trayLoc, Key.of(trayFurnitureId));
            if (furniture == null) {
                plugin.getLogger().warning("Cannot find tray furniture: " + trayFurnitureId);
                return;
            }

            markTrayFurniture(furniture);
            trayFurnitureCache.put(trayPos, furniture);

            Map<BlockPos, BlockPos> worldTrays = cookingPotTrays.computeIfAbsent(
                    world.getUID(), k -> new ConcurrentHashMap<>());
            worldTrays.put(potPos, trayPos);

            if (plugin.getConfig().getBoolean("debug", false)) {
                plugin.getLogger().info("Auto-placed tray at " + trayLoc + " for cooking pot at " + potPos);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to place tray furniture: " + e.getMessage());
        }
    }

    public void checkAndPlaceTray(Location cookingBlockLocation) {
        if (cookingBlockLocation == null || cookingBlockLocation.getWorld() == null) {
            return;
        }
        checkAndPlaceTray(
                cookingBlockLocation.getWorld(),
                new BlockPos(cookingBlockLocation.getBlockX(), cookingBlockLocation.getBlockY(), cookingBlockLocation.getBlockZ())
        );
    }

    public void removeTrayIfAutoPlaced(World world, BlockPos potPos) {
        if (!enabled || world == null) return;

        Map<BlockPos, BlockPos> worldTrays = cookingPotTrays.get(world.getUID());
        if (worldTrays == null) return;

        BlockPos trayPos = worldTrays.remove(potPos);
        if (trayPos == null) return;

        removeTrayAt(world, trayPos);
    }

    public void removeTrayIfAutoPlaced(Location cookingBlockLocation) {
        if (cookingBlockLocation == null || cookingBlockLocation.getWorld() == null) {
            return;
        }
        removeTrayIfAutoPlaced(
                cookingBlockLocation.getWorld(),
                new BlockPos(cookingBlockLocation.getBlockX(), cookingBlockLocation.getBlockY(), cookingBlockLocation.getBlockZ())
        );
    }

    private boolean isPotOrSkilletAt(World world, BlockPos pos) {
        if (CookingPotBlockBehavior.isCookingPotBlock(world, new BlockPosKey(pos))) {
            return true;
        }
        Location location = new Location(world, pos.x(), pos.y(), pos.z());
        return isSkilletBlock(location);
    }

    private boolean shouldHaveTray(World world, BlockPos potPos) {
        HeatSourceConfig heatConfig = plugin.getHeatSourceConfig();
        if (heatConfig == null) {
            return false;
        }

        Location belowLoc = new Location(world, potPos.x(), potPos.y() - 1, potPos.z());
        Block blockBelow = belowLoc.getBlock();
        if (heatConfig.isHeatSource(blockBelow)) {
            return isValidTraySupport(blockBelow);
        }

        if (heatConfig.isConductor(blockBelow)) {
            Block blockTwoBelow = world.getBlockAt(potPos.x(), potPos.y() - 2, potPos.z());
            return heatConfig.isHeatSource(blockTwoBelow) && isValidTraySupport(blockBelow);
        }

        return false;
    }

    private Location getTrayLocation(World world, BlockPos potPos) {
        return new Location(world, potPos.x() + xOffset, potPos.y() + yOffset, potPos.z() + zOffset);
    }

    private void syncAllTrays() {
        if (!enabled) {
            return;
        }

        for (World world : Bukkit.getWorlds()) {
            syncWorld(world);
        }
    }

    private void syncWorld(World world) {
        Set<BlockPos> validPotPositions = new HashSet<>();
        syncCookingPotTrays(world, validPotPositions);
        syncSkilletTrays(world, validPotPositions);

        Map<BlockPos, BlockPos> worldTrays = cookingPotTrays.get(world.getUID());
        if (worldTrays == null || worldTrays.isEmpty()) {
            return;
        }

        for (var entry : worldTrays.entrySet()) {
            BlockPos potPos = entry.getKey();
            if (!validPotPositions.contains(potPos) && !isPotOrSkilletAt(world, potPos)) {
                removeTrayIfAutoPlaced(world, potPos);
            }
        }
    }

    private void syncCookingPotTrays(World world, Set<BlockPos> validPositions) {
        for (var entry : CookingPotBlockBehavior.getBlockEntityEntries(world)) {
            BlockPosKey key = entry.getKey();
            BlockPos potPos = key.toBlockPos();
            validPositions.add(potPos);

            if (shouldHaveTray(world, potPos)) {
                checkAndPlaceTray(world, potPos);
            } else {
                removeTrayIfAutoPlaced(world, potPos);
            }
        }
    }

    private void syncSkilletTrays(World world, Set<BlockPos> validPositions) {
        SkilletManager skilletManager = plugin.getSkilletManager();
        if (skilletManager == null) {
            return;
        }

        for (Location skilletLocation : skilletManager.getTrackedLocations(world)) {
            BlockPos skilletPos = new BlockPos(
                    skilletLocation.getBlockX(),
                    skilletLocation.getBlockY(),
                    skilletLocation.getBlockZ()
            );
            validPositions.add(skilletPos);

            if (shouldHaveTray(world, skilletPos)) {
                checkAndPlaceTray(world, skilletPos);
            } else {
                removeTrayIfAutoPlaced(world, skilletPos);
            }
        }
    }

    private boolean isSkilletBlock(Location location) {
        return CustomBlockUtils.hasId(location, Constants.BLOCK_SKILLET);
    }

    private boolean canPlaceTrayAt(Block block) {
        HeatSourceConfig heatConfig = plugin.getHeatSourceConfig();
        return (heatConfig != null && (heatConfig.isHeatSource(block) || heatConfig.isConductor(block)))
                || isReplaceable(block);
    }

    private boolean isValidTraySupport(Block block) {
        return !requireNonFullSupport || isNonFullSupport(block);
    }

    private boolean isNonFullSupport(Block block) {
        if (block == null) {
            return false;
        }

        Material type = block.getType();
        if (type == Material.HOPPER) {
            return false;
        }

        if (!type.isOccluding()) {
            return true;
        }

        BoundingBox box = block.getBoundingBox();
        double height = box.getMaxY() - box.getMinY();
        return height < 0.99D;
    }

    private boolean isReplaceable(Block block) {
        Material type = block.getType();
        return type.isAir()
                || type == Material.WATER
                || type == Material.SEAGRASS
                || type == Material.TALL_SEAGRASS
                || type == Material.SHORT_GRASS
                || type == Material.TALL_GRASS
                || type == Material.FERN
                || type == Material.LARGE_FERN
                || type == Material.SNOW;
    }

    private void markTrayFurniture(BukkitFurniture furniture) {
        Entity entity = furniture.bukkitEntity();
        if (entity != null && entity.isValid()) {
            entity.getPersistentDataContainer().set(trayMarkerKey, PersistentDataType.BYTE, (byte) 1);
        }
    }

    public boolean isAutoPlacedTray(BukkitFurniture furniture) {
        if (furniture == null) {
            return false;
        }
        Entity entity = furniture.bukkitEntity();
        return entity != null
                && entity.isValid()
                && entity.getPersistentDataContainer().has(trayMarkerKey, PersistentDataType.BYTE);
    }

    private void removeTrayAt(World world, BlockPos trayPos) {
        try {
            BukkitFurniture furniture = trayFurnitureCache.remove(trayPos);
            if (furniture == null) {
                Location location = new Location(world, trayPos.x(), trayPos.y(), trayPos.z());
                furniture = findTrayFurniture(world, location);
            }

            if (furniture == null) {
                return;
            }

            Entity entity = furniture.bukkitEntity();
            if (entity != null && entity.isValid()) {
                CraftEngineFurniture.remove(entity, false, false);
            }

            if (plugin.getConfig().getBoolean("debug", false)) {
                plugin.getLogger().info("Removed auto-placed tray at " + trayPos);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to remove tray furniture: " + e.getMessage());
        }
    }

    @Nullable
    private BukkitFurniture findTrayFurniture(World world, Location location) {
        BlockPos trayPos = new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        BukkitFurniture cached = trayFurnitureCache.get(trayPos);
        if (cached != null && cached.bukkitEntity() != null && cached.bukkitEntity().isValid()) {
            return cached;
        }
        trayFurnitureCache.remove(trayPos);

        double bx = location.getBlockX();
        double by = location.getBlockY();
        double bz = location.getBlockZ();
        for (Entity entity : world.getNearbyEntities(
                new BoundingBox(bx, by, bz, bx + 1, by + 1, bz + 1))) {
            if (!(entity instanceof org.bukkit.entity.ItemDisplay)) continue;

            BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
            if (furniture != null && furniture.id().toString().equals(trayFurnitureId)) {
                return furniture;
            }
        }
        return null;
    }

    public void cleanupWorld(UUID worldId) {
        World world = Bukkit.getWorld(worldId);
        Map<BlockPos, BlockPos> worldTrays = cookingPotTrays.remove(worldId);
        if (worldTrays != null) {
            if (world != null) {
                for (BlockPos trayPos : worldTrays.values()) {
                    removeTrayAt(world, trayPos);
                }
            }
            worldTrays.clear();
        }
    }

    private void removeAllTrays() {
        for (World world : Bukkit.getWorlds()) {
            Map<BlockPos, BlockPos> worldTrays = cookingPotTrays.get(world.getUID());
            if (worldTrays == null || worldTrays.isEmpty()) {
                continue;
            }
            for (BlockPos trayPos : worldTrays.values()) {
                removeTrayAt(world, trayPos);
            }
            worldTrays.clear();
        }
        cookingPotTrays.clear();
    }

    public void cleanupAll() {
        stop();
        removeAllTrays();
    }
}
