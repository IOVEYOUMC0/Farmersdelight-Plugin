package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.config.HeatSourceConfig;
import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.Constants;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TrayManager {

    private static final long DEFAULT_SYNC_INTERVAL_TICKS = 100L;
    private static final int DEFAULT_SYNC_BATCH_SIZE = 32;
    private static final Map<UUID, Map<BlockPos, BlockPos>> cookingPotTrays = new ConcurrentHashMap<>();

    private final FarmersDelightPlugin plugin;
    private final Map<BlockPos, BukkitFurniture> trayFurnitureCache = new ConcurrentHashMap<>();
    private final Map<UUID, World> knownWorlds = new ConcurrentHashMap<>();
    private final Set<TraySyncKey> scheduledTraySyncs = ConcurrentHashMap.newKeySet();
    private String trayFurnitureId;
    private double xOffset;
    private double yOffset;
    private double zOffset;
    private boolean requireNonFullSupport;
    private NamespacedKey trayMarkerKey;
    private boolean enabled;
    private long syncIntervalTicks;
    private int syncBatchSize;
    private PluginTask syncTask;
    private int cookingPotSyncCursor;
    private int skilletSyncCursor;
    private int trayOwnerSyncCursor;

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

    private void trackWorld(World world) {
        if (world != null) {
            knownWorlds.put(world.getUID(), world);
        }
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
        syncIntervalTicks = Math.max(20L, config.getLong("sync-interval-ticks", DEFAULT_SYNC_INTERVAL_TICKS));
        syncBatchSize = Math.max(1, config.getInt("sync-batch-size", DEFAULT_SYNC_BATCH_SIZE));
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

        trayFurnitureCache.clear();
        scheduledTraySyncs.clear();
        cookingPotSyncCursor = 0;
        skilletSyncCursor = 0;
        trayOwnerSyncCursor = 0;
        start();
        syncAllTraysNow();
    }

    private void start() {
        stop();
        syncTask = plugin.scheduler().runRepeating(this::syncAllTraysBatched, 1L, syncIntervalTicks);
    }

    public void stop() {
        if (syncTask != null) {
            syncTask.cancel();
            syncTask = null;
        }
    }

    public void checkAndPlaceTray(World world, BlockPos potPos) {
        if (!enabled || world == null || potPos == null) return;
        trackWorld(world);

        if (!shouldHaveTray(world, potPos)) {
            return;
        }

        Location trayLoc = getTrayLocation(world, potPos);
        if (trayLoc.getY() < world.getMinHeight() || trayLoc.getY() > world.getMaxHeight()) {
            return;
        }

        BlockPos trayPos = new BlockPos(trayLoc.getBlockX(), trayLoc.getBlockY(), trayLoc.getBlockZ());
        Map<BlockPos, BlockPos> worldTrays = cookingPotTrays.computeIfAbsent(
                world.getUID(), k -> new ConcurrentHashMap<>());
        BlockPos existingTrayPos = worldTrays.get(potPos);
        if (existingTrayPos != null) {
            BukkitFurniture existing = trayFurnitureCache.get(existingTrayPos);
            if (existing != null && existing.bukkitEntity() != null && existing.bukkitEntity().isValid()) {
                return;
            }

            existing = findTrayFurniture(world, new Location(world, existingTrayPos.x(), existingTrayPos.y(), existingTrayPos.z()));
            if (existing != null && existing.bukkitEntity() != null && existing.bukkitEntity().isValid()) {
                trayFurnitureCache.put(existingTrayPos, existing);
                return;
            }

            worldTrays.remove(potPos);
        }

        BukkitFurniture cached = trayFurnitureCache.get(trayPos);
        if (cached != null && cached.bukkitEntity() != null && cached.bukkitEntity().isValid()) {
            worldTrays.put(potPos, trayPos);
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
            worldTrays.put(potPos, trayPos);

            if (plugin.isDebugEnabled()) {
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
        trackWorld(world);

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

    private void syncAllTraysBatched() {
        if (!enabled) {
            return;
        }

        cookingPotSyncCursor = scheduleBatchedTraySyncs(
                CookingPotBlockBehavior.getBlockEntityLocations(),
                cookingPotSyncCursor,
                true
        );

        SkilletManager skilletManager = plugin.getSkilletManager();
        if (skilletManager != null) {
            skilletSyncCursor = scheduleBatchedTraySyncs(
                    skilletManager.getTrackedLocations(),
                    skilletSyncCursor,
                    true
            );
        }

        trayOwnerSyncCursor = scheduleBatchedTraySyncs(
                getTrackedTrayOwnerLocations(),
                trayOwnerSyncCursor,
                true
        );
    }

    private void syncAllTraysNow() {
        if (!enabled) {
            return;
        }

        scheduleBatchedTraySyncs(CookingPotBlockBehavior.getBlockEntityLocations(), 0, false);

        SkilletManager skilletManager = plugin.getSkilletManager();
        if (skilletManager != null) {
            scheduleBatchedTraySyncs(skilletManager.getTrackedLocations(), 0, false);
        }

        scheduleBatchedTraySyncs(getTrackedTrayOwnerLocations(), 0, false);
    }

    private int scheduleBatchedTraySyncs(Collection<Location> rawLocations, int cursor, boolean batched) {
        if (rawLocations == null || rawLocations.isEmpty()) {
            return 0;
        }

        List<Location> locations = new ArrayList<>(rawLocations);
        int size = locations.size();
        int start = cursor;
        if (start >= size) {
            start = 0;
        }

        int budget = batched ? Math.min(syncBatchSize, size) : size;
        for (int processed = 0; processed < budget; processed++) {
            Location location = locations.get((start + processed) % size);
            scheduleTraySync(location);
        }

        return batched ? (start + Math.max(1, budget)) % size : 0;
    }

    private void scheduleTraySync(Location location) {
        if (location == null || location.getWorld() == null) {
            return;
        }

        Location normalized = new Location(
                location.getWorld(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ()
        );
        trackWorld(normalized.getWorld());

        if (!plugin.scheduler().isFolia()) {
            syncTrayOwner(normalized);
            return;
        }

        TraySyncKey key = new TraySyncKey(
                normalized.getWorld().getUID(),
                normalized.getBlockX(),
                normalized.getBlockY(),
                normalized.getBlockZ()
        );
        if (!scheduledTraySyncs.add(key)) {
            return;
        }
        try {
            plugin.scheduler().runAt(normalized, () -> {
                try {
                    syncTrayOwner(normalized);
                } finally {
                    scheduledTraySyncs.remove(key);
                }
            });
        } catch (RuntimeException e) {
            scheduledTraySyncs.remove(key);
        }
    }

    private void syncTrayOwner(Location location) {
        if (!enabled || location == null || location.getWorld() == null) {
            return;
        }

        World world = location.getWorld();
        trackWorld(world);
        BlockPos ownerPos = new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        if (!isPotOrSkilletAt(world, ownerPos)) {
            removeTrayIfAutoPlaced(world, ownerPos);
            return;
        }

        if (shouldHaveTray(world, ownerPos)) {
            checkAndPlaceTray(world, ownerPos);
        } else {
            removeTrayIfAutoPlaced(world, ownerPos);
        }
    }

    private List<Location> getTrackedTrayOwnerLocations() {
        List<Location> locations = new ArrayList<>();
        for (Map.Entry<UUID, Map<BlockPos, BlockPos>> worldEntry : cookingPotTrays.entrySet()) {
            World world = knownWorlds.get(worldEntry.getKey());
            if (world == null) {
                continue;
            }
            for (BlockPos ownerPos : worldEntry.getValue().keySet()) {
                locations.add(new Location(world, ownerPos.x(), ownerPos.y(), ownerPos.z()));
            }
        }
        return locations;
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

    public int cleanupInvalidAutoTrays() {
        if (plugin.scheduler().isFolia()) {
            int scheduled = 0;
            for (Location location : getTrackedTrayOwnerLocations()) {
                scheduleTraySync(location);
                scheduled++;
            }
            return scheduled;
        }

        int removed = 0;
        for (Map.Entry<UUID, Map<BlockPos, BlockPos>> worldEntry : cookingPotTrays.entrySet()) {
            World world = knownWorlds.get(worldEntry.getKey());
            if (world == null) {
                continue;
            }
            Map<BlockPos, BlockPos> worldTrays = worldEntry.getValue();
            if (worldTrays == null || worldTrays.isEmpty()) {
                continue;
            }
            for (var entry : List.copyOf(worldTrays.entrySet())) {
                BlockPos ownerPos = entry.getKey();
                BlockPos trayPos = entry.getValue();
                if (trayPos == null) {
                    worldTrays.remove(ownerPos);
                    continue;
                }
                if (isValidAutoTrayOwner(world, ownerPos)) {
                    cookingPotTrays.computeIfAbsent(world.getUID(), ignored -> new ConcurrentHashMap<>())
                            .put(ownerPos, trayPos);
                    continue;
                }

                try {
                    removeTrayAt(world, trayPos);
                    worldTrays.remove(ownerPos);
                    removed++;
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to cleanup invalid auto tray at " + trayPos + ": " + e.getMessage());
                }
            }
        }
        return removed;
    }

    private boolean isValidAutoTrayOwner(World world, BlockPos ownerPos) {
        return ownerPos != null && isPotOrSkilletAt(world, ownerPos) && shouldHaveTray(world, ownerPos);
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

            if (plugin.isDebugEnabled()) {
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
        World world = knownWorlds.remove(worldId);
        scheduledTraySyncs.removeIf(key -> key.worldId().equals(worldId));
        Map<BlockPos, BlockPos> worldTrays = cookingPotTrays.remove(worldId);
        if (worldTrays != null) {
            if (world != null) {
                for (BlockPos trayPos : worldTrays.values()) {
                    scheduleRemoveTrayAt(world, trayPos);
                }
            }
            worldTrays.clear();
        }
    }

    private void removeAllTrays() {
        for (Map.Entry<UUID, Map<BlockPos, BlockPos>> worldEntry : cookingPotTrays.entrySet()) {
            World world = knownWorlds.get(worldEntry.getKey());
            if (world == null) {
                continue;
            }
            Map<BlockPos, BlockPos> worldTrays = worldEntry.getValue();
            if (worldTrays == null || worldTrays.isEmpty()) {
                continue;
            }
            for (BlockPos trayPos : worldTrays.values()) {
                scheduleRemoveTrayAt(world, trayPos);
            }
            worldTrays.clear();
        }
        cookingPotTrays.clear();
        scheduledTraySyncs.clear();
    }

    private void scheduleRemoveTrayAt(World world, BlockPos trayPos) {
        if (world == null || trayPos == null) {
            return;
        }

        Location trayLocation = new Location(world, trayPos.x(), trayPos.y(), trayPos.z());
        if (!plugin.scheduler().isFolia()) {
            removeTrayAt(world, trayPos);
            return;
        }

        plugin.scheduler().runAt(trayLocation, () -> removeTrayAt(world, trayPos));
    }

    public void cleanupAll() {
        stop();
        removeAllTrays();
    }

    private record TraySyncKey(UUID worldId, int x, int y, int z) {
    }
}

