package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.block.behavior.CookingPotBlockBehavior;
import com.huidu.farmersdelight.block.behavior.SkilletBlockBehavior;
import com.huidu.farmersdelight.config.HeatSourceConfig;
import com.huidu.farmersdelight.i18n.I18n;
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
import java.util.concurrent.ConcurrentLinkedQueue;

public class TrayManager {

    private static final long DEFAULT_SYNC_INTERVAL_TICKS = 100L;
    private static final int DEFAULT_SYNC_BATCH_SIZE = 32;
    private static final Map<UUID, Map<BlockPos, BlockPos>> cookingPotTrays = new ConcurrentHashMap<>();

    private final FarmersDelightPlugin plugin;
    private final Map<BlockPos, BukkitFurniture> trayFurnitureCache = new ConcurrentHashMap<>();
    private final Map<UUID, World> knownWorlds = new ConcurrentHashMap<>();
    private final Set<TraySyncKey> scheduledTraySyncs = ConcurrentHashMap.newKeySet();
    private final Queue<Location> queuedTraySyncs = new ConcurrentLinkedQueue<>();
    private final Set<TraySyncKey> queuedTraySyncKeys = ConcurrentHashMap.newKeySet();
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
    private PluginTask queuedSyncTask;

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
        ConfigurationSection config = plugin.getFirstConfigSection("cooking-pot.tray", "tray");
        if (config == null) {
            config = plugin.getConfig().createSection("cooking-pot.tray");
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
        queuedTraySyncs.clear();
        queuedTraySyncKeys.clear();
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
        if (queuedSyncTask != null) {
            queuedSyncTask.cancel();
            queuedSyncTask = null;
        }
    }

    public void checkAndPlaceTray(World world, BlockPos potPos) {
        if (!enabled || world == null || potPos == null) return;
        trackWorld(world);

        if (!shouldHaveTray(world, potPos)) {
            removeTrayIfAutoPlaced(world, potPos);
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
            if (isAutoPlacedTray(existing)) {
                return;
            }

            existing = findAutoTrayFurniture(world, new Location(world, existingTrayPos.x(), existingTrayPos.y(), existingTrayPos.z()));
            if (isAutoPlacedTray(existing)) {
                trayFurnitureCache.put(existingTrayPos, existing);
                return;
            }

            worldTrays.remove(potPos);
        }

        BukkitFurniture cached = trayFurnitureCache.get(trayPos);
        if (isAutoPlacedTray(cached)) {
            worldTrays.put(potPos, trayPos);
            return;
        }
        trayFurnitureCache.remove(trayPos);

        List<BukkitFurniture> existingFurnitures = findTrayFurnitures(world, trayLoc);
        BukkitFurniture existingAutoTray = firstAutoTray(existingFurnitures);
        if (existingAutoTray != null) {
            removeDuplicateAutoTrays(world, trayPos, existingFurnitures, existingAutoTray, "duplicate auto tray before place");
            trayFurnitureCache.put(trayPos, existingAutoTray);
            worldTrays.put(potPos, trayPos);
            return;
        }

        if (!existingFurnitures.isEmpty()) {
            return;
        }

        if (!canPlaceTrayAt(trayLoc.getBlock())) {
            return;
        }

        try {
            BukkitFurniture furniture = CraftEngineFurniture.place(trayLoc, Key.of(trayFurnitureId));
            if (furniture == null) {
                plugin.getLogger().warning(I18n.formatConsole("tray.missing_furniture", "id", trayFurnitureId));
                return;
            }

            markTrayFurniture(furniture);
            trayFurnitureCache.put(trayPos, furniture);
            worldTrays.put(potPos, trayPos);

            if (plugin.isDebugEnabled("tray")) {
                plugin.getLogger().info(I18n.formatConsole("tray.placed", "location", trayLoc, "pot", potPos));
            }
        } catch (Exception e) {
            plugin.getLogger().warning(I18n.formatConsole("tray.place_failed", "error", e.getMessage()));
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
        if (!enabled || world == null || potPos == null) return;
        trackWorld(world);

        Map<BlockPos, BlockPos> worldTrays = cookingPotTrays.get(world.getUID());
        BlockPos trayPos = worldTrays != null ? worldTrays.remove(potPos) : null;
        if (trayPos == null) {
            Location trayLoc = getTrayLocation(world, potPos);
            trayPos = new BlockPos(trayLoc.getBlockX(), trayLoc.getBlockY(), trayLoc.getBlockZ());
        }

        removeTrayAt(world, trayPos, "owner removed");
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

    public void syncAroundSupportChange(Location supportLocation) {
        if (!enabled || supportLocation == null || supportLocation.getWorld() == null) {
            return;
        }

        Location normalized = new Location(
                supportLocation.getWorld(),
                supportLocation.getBlockX(),
                supportLocation.getBlockY(),
                supportLocation.getBlockZ()
        );
        trackWorld(normalized.getWorld());
        plugin.scheduler().runLaterAt(normalized, () -> {
            queueTraySync(normalized.clone().add(0, 1, 0));
            queueTraySync(normalized.clone().add(0, 2, 0));
        }, 1L);
    }

    public void queueTraySync(Location ownerLocation) {
        if (!enabled || ownerLocation == null || ownerLocation.getWorld() == null) {
            return;
        }

        TraySyncKey key = new TraySyncKey(
                ownerLocation.getWorld().getUID(),
                ownerLocation.getBlockX(),
                ownerLocation.getBlockY(),
                ownerLocation.getBlockZ()
        );
        if (!queuedTraySyncKeys.add(key)) {
            return;
        }

        queuedTraySyncs.add(new Location(ownerLocation.getWorld(), key.x(), key.y(), key.z()));
        trackWorld(ownerLocation.getWorld());
        ensureQueuedSyncTask();
    }

    private void ensureQueuedSyncTask() {
        if (queuedSyncTask != null) {
            return;
        }
        queuedSyncTask = plugin.scheduler().runRepeating(this::processQueuedTraySyncs, 1L, 1L);
    }

    private void processQueuedTraySyncs() {
        int budget = Math.max(1, syncBatchSize);
        for (int processed = 0; processed < budget; processed++) {
            Location location = queuedTraySyncs.poll();
            if (location == null) {
                stopQueuedSyncTaskIfIdle();
                return;
            }

            queuedTraySyncKeys.remove(new TraySyncKey(
                    location.getWorld().getUID(),
                    location.getBlockX(),
                    location.getBlockY(),
                    location.getBlockZ()
            ));
            scheduleTraySync(location);
        }
        stopQueuedSyncTaskIfIdle();
    }

    private void stopQueuedSyncTaskIfIdle() {
        if (!queuedTraySyncs.isEmpty() || queuedSyncTask == null) {
            return;
        }
        queuedSyncTask.cancel();
        queuedSyncTask = null;
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

        Set<TraySyncKey> scheduledThisRun = new HashSet<>(syncBatchSize * 3);
        cookingPotSyncCursor = scheduleBatchedTraySyncs(
                CookingPotBlockBehavior.getBlockEntityLocations(),
                cookingPotSyncCursor,
                true,
                scheduledThisRun
        );

        SkilletManager skilletManager = plugin.getSkilletManager();
        if (skilletManager != null) {
            skilletSyncCursor = scheduleBatchedTraySyncs(
                    skilletManager.getTrackedLocations(),
                    skilletSyncCursor,
                    true,
                    scheduledThisRun
            );
        }

        trayOwnerSyncCursor = scheduleBatchedTraySyncs(
                getTrackedTrayOwnerLocations(),
                trayOwnerSyncCursor,
                true,
                scheduledThisRun
        );
    }

    private void syncAllTraysNow() {
        if (!enabled) {
            return;
        }

        Set<TraySyncKey> scheduledThisRun = new HashSet<>();
        scheduleBatchedTraySyncs(CookingPotBlockBehavior.getBlockEntityLocations(), 0, false, scheduledThisRun);

        SkilletManager skilletManager = plugin.getSkilletManager();
        if (skilletManager != null) {
            scheduleBatchedTraySyncs(skilletManager.getTrackedLocations(), 0, false, scheduledThisRun);
        }

        scheduleBatchedTraySyncs(getTrackedTrayOwnerLocations(), 0, false, scheduledThisRun);
    }

    private int scheduleBatchedTraySyncs(
            Collection<Location> rawLocations,
            int cursor,
            boolean batched,
            Set<TraySyncKey> scheduledThisRun
    ) {
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
            scheduleTraySync(location, scheduledThisRun);
        }

        return batched ? (start + Math.max(1, budget)) % size : 0;
    }

    private void scheduleTraySync(Location location) {
        scheduleTraySync(location, null);
    }

    private void scheduleTraySync(Location location, @Nullable Set<TraySyncKey> scheduledThisRun) {
        if (location == null || location.getWorld() == null) {
            return;
        }

        TraySyncKey key = new TraySyncKey(
                location.getWorld().getUID(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ()
        );
        if (scheduledThisRun != null && !scheduledThisRun.add(key)) {
            return;
        }

        Location normalized = new Location(
                location.getWorld(),
                key.x(),
                key.y(),
                key.z()
        );
        trackWorld(normalized.getWorld());

        if (!plugin.scheduler().isFolia()) {
            syncTrayOwner(normalized);
            return;
        }

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
        return CustomBlockUtils.hasBehavior(location, SkilletBlockBehavior.class)
                || CustomBlockUtils.hasId(location, Constants.BLOCK_SKILLET);
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
                    removeTrayAt(world, trayPos, "cleanup invalid owner");
                    worldTrays.remove(ownerPos);
                    removed++;
                } catch (Exception e) {
                    plugin.getLogger().warning(I18n.formatConsole("tray.cleanup_invalid_failed",
                            "pos", trayPos,
                            "error", e.getMessage()));
                }
            }
        }
        return removed;
    }

    private boolean isValidAutoTrayOwner(World world, BlockPos ownerPos) {
        return ownerPos != null && isPotOrSkilletAt(world, ownerPos) && shouldHaveTray(world, ownerPos);
    }

    private void removeTrayAt(World world, BlockPos trayPos) {
        removeTrayAt(world, trayPos, "unspecified");
    }

    private void removeTrayAt(World world, BlockPos trayPos, String reason) {
        try {
            BukkitFurniture furniture = trayFurnitureCache.remove(trayPos);
            if (furniture == null) {
                Location location = new Location(world, trayPos.x(), trayPos.y(), trayPos.z());
                furniture = findAutoTrayFurniture(world, location);
            }

            if (!isAutoPlacedTray(furniture)) {
                return;
            }

            Entity entity = furniture.bukkitEntity();
            if (entity != null && entity.isValid()) {
                CraftEngineFurniture.remove(entity, false, false);
            }

            if (plugin.isDebugEnabled("tray")) {
                plugin.getLogger().info(I18n.formatConsole("tray.removed", "pos", trayPos, "reason", reason));
            }
        } catch (Exception e) {
            plugin.getLogger().warning(I18n.formatConsole("tray.remove_failed", "error", e.getMessage()));
        }
    }

    @Nullable
    private BukkitFurniture findAutoTrayFurniture(World world, Location location) {
        List<BukkitFurniture> furnitures = findTrayFurnitures(world, location);
        BukkitFurniture autoTray = firstAutoTray(furnitures);
        if (autoTray != null) {
            removeDuplicateAutoTrays(world, new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ()),
                    furnitures, autoTray, "duplicate auto tray found");
        }
        return autoTray;
    }

    private List<BukkitFurniture> findTrayFurnitures(World world, Location location) {
        if (world == null || location == null) {
            return List.of();
        }
        BlockPos trayPos = new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        BukkitFurniture cached = trayFurnitureCache.get(trayPos);
        if (isAutoPlacedTray(cached)) {
            return List.of(cached);
        }
        trayFurnitureCache.remove(trayPos);

        List<BukkitFurniture> furnitures = new ArrayList<>();
        double bx = location.getBlockX();
        double by = location.getBlockY();
        double bz = location.getBlockZ();
        for (Entity entity : world.getNearbyEntities(
                new BoundingBox(bx, by, bz, bx + 1, by + 1, bz + 1))) {
            if (!(entity instanceof org.bukkit.entity.ItemDisplay)) continue;

            BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
            if (furniture != null && furniture.id().toString().equals(trayFurnitureId)) {
                furnitures.add(furniture);
            }
        }
        return furnitures;
    }

    @Nullable
    private BukkitFurniture firstAutoTray(Collection<BukkitFurniture> furnitures) {
        if (furnitures == null || furnitures.isEmpty()) {
            return null;
        }
        for (BukkitFurniture furniture : furnitures) {
            if (isAutoPlacedTray(furniture)) {
                return furniture;
            }
        }
        return null;
    }

    private void removeDuplicateAutoTrays(World world, BlockPos trayPos, Collection<BukkitFurniture> furnitures,
                                          BukkitFurniture keep, String reason) {
        if (furnitures == null || furnitures.isEmpty()) {
            return;
        }
        for (BukkitFurniture furniture : furnitures) {
            if (furniture == keep || !isAutoPlacedTray(furniture)) {
                continue;
            }
            Entity entity = furniture.bukkitEntity();
            if (entity != null && entity.isValid()) {
                CraftEngineFurniture.remove(entity, false, false);
            }
        }
        if (isAutoPlacedTray(keep)) {
            trayFurnitureCache.put(trayPos, keep);
        } else {
            trayFurnitureCache.remove(trayPos);
        }
        if (plugin.isDebugEnabled("tray")) {
            plugin.getLogger().info(I18n.formatConsole("tray.duplicates_cleaned", "pos", trayPos, "reason", reason));
        }
    }

    public void cleanupWorld(UUID worldId) {
        World world = knownWorlds.remove(worldId);
        scheduledTraySyncs.removeIf(key -> key.worldId().equals(worldId));
        queuedTraySyncKeys.removeIf(key -> key.worldId().equals(worldId));
        queuedTraySyncs.removeIf(location -> location != null
                && location.getWorld() != null
                && location.getWorld().getUID().equals(worldId));
        stopQueuedSyncTaskIfIdle();
        Map<BlockPos, BlockPos> worldTrays = cookingPotTrays.remove(worldId);
        if (worldTrays != null) {
            if (world != null) {
                for (BlockPos trayPos : worldTrays.values()) {
                    scheduleRemoveTrayAt(world, trayPos, "world cleanup");
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
                scheduleRemoveTrayAt(world, trayPos, "remove all trays");
            }
            worldTrays.clear();
        }
        cookingPotTrays.clear();
        scheduledTraySyncs.clear();
        queuedTraySyncs.clear();
        queuedTraySyncKeys.clear();
    }

    private void scheduleRemoveTrayAt(World world, BlockPos trayPos, String reason) {
        if (world == null || trayPos == null) {
            return;
        }

        Location trayLocation = new Location(world, trayPos.x(), trayPos.y(), trayPos.z());
        if (!plugin.scheduler().isFolia()) {
            removeTrayAt(world, trayPos, reason);
            return;
        }

        plugin.scheduler().runAt(trayLocation, () -> removeTrayAt(world, trayPos, reason));
    }

    public void cleanupAll() {
        stop();
        removeAllTrays();
    }

    private record TraySyncKey(UUID worldId, int x, int y, int z) {
    }
}

