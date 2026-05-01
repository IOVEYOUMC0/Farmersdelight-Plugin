package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.config.HeatSourceConfig;
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

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TrayManager {

    private static final long SYNC_INTERVAL_TICKS = 20L;
    private static final Map<UUID, Map<BlockPos, BlockPos>> cookingPotTrays = new ConcurrentHashMap<>();

    private final FarmersDelightPlugin plugin;
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
        if (syncTask == null) {
            start();
        }
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
        if (findTrayFurniture(world, trayLoc) != null) {
            return;
        }

        placeTray(world, trayLoc, potPos);
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

    private void placeTray(World world, Location location, BlockPos potPos) {
        try {
            if (location.getY() < world.getMinHeight() || location.getY() > world.getMaxHeight()) {
                return;
            }

            if (!canPlaceTrayAt(location.getBlock())) {
                return;
            }

            if (findAnyFurnitureAt(world, location) != null) {
                return;
            }

            BukkitFurniture furniture = CraftEngineFurniture.place(location, Key.of(trayFurnitureId));
            if (furniture == null) {
                plugin.getLogger().warning("Cannot find tray furniture: " + trayFurnitureId);
                return;
            }

            markTrayFurniture(furniture);

            Map<BlockPos, BlockPos> worldTrays = cookingPotTrays.computeIfAbsent(
                    world.getUID(), k -> new ConcurrentHashMap<>());
            worldTrays.put(potPos, new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ()));

            if (plugin.getConfig().getBoolean("debug", false)) {
                plugin.getLogger().info("Auto-placed tray at " + location + " for cooking pot at " + potPos);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to place tray furniture: " + e.getMessage());
        }
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

        for (BlockPos potPos : Set.copyOf(worldTrays.keySet())) {
            if (!validPotPositions.contains(potPos)) {
                removeTrayIfAutoPlaced(world, potPos);
            }
        }
    }

    private void syncCookingPotTrays(World world, Set<BlockPos> validPositions) {
        for (var entry : CookingPotBlockBehavior.getAllBlockEntities(world).entrySet()) {
            BlockPos potPos = entry.getKey().toBlockPos();
            validPositions.add(potPos);

            if (!CookingPotBlockBehavior.isCookingPotBlock(world, entry.getKey())) {
                removeTrayIfAutoPlaced(world, potPos);
                continue;
            }

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

            if (!isSkilletBlock(skilletLocation)) {
                removeTrayIfAutoPlaced(world, skilletPos);
                continue;
            }

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

        if (block.getType() == Material.HOPPER || block.getState() instanceof org.bukkit.block.Hopper) {
            return false;
        }

        if (!block.getType().isOccluding()) {
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
        Location location = new Location(world, trayPos.x(), trayPos.y(), trayPos.z());

        try {
            BukkitFurniture furniture = findTrayFurniture(world, location);
            if (furniture == null) {
                return;
            }

            Entity entity = furniture.bukkitEntity();
            if (entity == null || !entity.isValid()) {
                return;
            }

            if (!entity.getPersistentDataContainer().has(trayMarkerKey, PersistentDataType.BYTE)) {
                return;
            }

            CraftEngineFurniture.remove(entity, false, false);

            if (plugin.getConfig().getBoolean("debug", false)) {
                plugin.getLogger().info("Removed auto-placed tray at " + location);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to remove tray furniture: " + e.getMessage());
        }
    }

    @Nullable
    private BukkitFurniture findTrayFurniture(World world, Location location) {
        for (Entity entity : world.getNearbyEntities(location, 1, 1, 1)) {
            if (!isSameBlock(entity.getLocation(), location)) {
                continue;
            }

            BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
            if (furniture != null && furniture.id().toString().equals(trayFurnitureId)) {
                return furniture;
            }
        }
        return null;
    }

    @Nullable
    private BukkitFurniture findAnyFurnitureAt(World world, Location location) {
        for (Entity entity : world.getNearbyEntities(location, 1, 1, 1)) {
            if (!isSameBlock(entity.getLocation(), location)) {
                continue;
            }

            BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
            if (furniture != null) {
                return furniture;
            }
        }
        return null;
    }

    private boolean isSameBlock(Location first, Location second) {
        return first.getWorld() != null
                && second.getWorld() != null
                && first.getWorld().equals(second.getWorld())
                && first.getBlockX() == second.getBlockX()
                && first.getBlockY() == second.getBlockY()
                && first.getBlockZ() == second.getBlockZ();
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
