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
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurnitureManager;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class TrayManager {

    private static final long DEFAULT_SYNC_INTERVAL_TICKS = 100L;
    private static final int DEFAULT_SYNC_BATCH_SIZE = 32;
    private static final long STARTUP_CLEANUP_DELAY_TICKS = 20L;
    private static final String DEFAULT_MARKER_KEY = "auto_tray_marker";
    private static final String MARKER_SCOREBOARD_PREFIX = "farmersdelight:auto_tray:";
    private static final String OWNER_SCOREBOARD_PREFIX = "farmersdelight:auto_tray_owner:";

    private final FarmersDelightPlugin plugin;
    private final Map<UUID, World> knownWorlds = new ConcurrentHashMap<>();
    private final Set<TraySyncKey> scheduledTraySyncs = ConcurrentHashMap.newKeySet();
    private final Queue<Location> queuedTraySyncs = new ConcurrentLinkedQueue<>();
    private final Set<TraySyncKey> queuedTraySyncKeys = ConcurrentHashMap.newKeySet();
    private final NamespacedKey defaultTrayMarkerKey;
    private final NamespacedKey trayOwnerWorldKey;
    private final NamespacedKey trayOwnerXKey;
    private final NamespacedKey trayOwnerYKey;
    private final NamespacedKey trayOwnerZKey;
    // Marks a tray the PLAYER placed by hand (stamped from FurniturePlaceEvent). Manual trays are never
    // auto-managed: the cooking pot/skillet never reclaim, absorb, or remove them, and /fd cleanup leaves
    // them alone. The auto tray is placed programmatically (no FurniturePlaceEvent) so it never gets this.
    private static final String MANUAL_TRAY_SCOREBOARD_TAG = "farmersdelight:manual_tray";
    private final NamespacedKey manualTrayMarkerKey;
    private String trayFurnitureId;
    private double xOffset;
    private double yOffset;
    private double zOffset;
    private boolean requireNonFullSupport;
    private NamespacedKey trayMarkerKey;
    private String trayScoreboardTag;
    private boolean enabled;
    private long syncIntervalTicks;
    private int syncBatchSize;
    private PluginTask syncTask;
    private int cookingPotSyncCursor;
    private int skilletSyncCursor;
    private PluginTask queuedSyncTask;
    private PluginTask startupCleanupTask;
    // Guards queuedSyncTask, which is started/stopped from queueTraySync callbacks on region threads.
    private final Object queuedSyncTaskLock = new Object();

    public TrayManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        this.defaultTrayMarkerKey = new NamespacedKey(plugin, DEFAULT_MARKER_KEY);
        this.trayOwnerWorldKey = new NamespacedKey(plugin, "auto_tray_owner_world");
        this.trayOwnerXKey = new NamespacedKey(plugin, "auto_tray_owner_x");
        this.trayOwnerYKey = new NamespacedKey(plugin, "auto_tray_owner_y");
        this.trayOwnerZKey = new NamespacedKey(plugin, "auto_tray_owner_z");
        this.manualTrayMarkerKey = new NamespacedKey(plugin, "manual_tray");
        loadConfig();
        start();
        scheduleStartupCleanup();
    }

    private void trackWorld(World world) {
        if (world != null) {
            knownWorlds.put(world.getUID(), world);
        }
    }

    private void loadConfig() {
        ConfigurationSection config = plugin.getFirstConfigSection("tray", "cooking-pot.tray");
        if (config == null) {
            // Use a detached empty section so missing config falls back to each field's default below,
            // without mutating the live FileConfiguration (createSection would inject an unexpected
            // empty section the user never wrote).
            config = new org.bukkit.configuration.MemoryConfiguration();
        }

        enabled = config.getBoolean("enabled", true);
        trayFurnitureId = config.getString("tray-block-id", "farmersdelight:tray");
        xOffset = config.getDouble("x-offset", 0.5D);
        yOffset = config.getDouble("y-offset", -1.0D);
        zOffset = config.getDouble("z-offset", 0.5D);
        requireNonFullSupport = config.getBoolean("require-non-full-support", true);
        syncIntervalTicks = Math.max(20L, config.getLong("sync-interval-ticks", DEFAULT_SYNC_INTERVAL_TICKS));
        syncBatchSize = Math.max(1, config.getInt("sync-batch-size", DEFAULT_SYNC_BATCH_SIZE));
        String markerKey = config.getString("marker-key", DEFAULT_MARKER_KEY);
        trayMarkerKey = createTrayMarkerKey(markerKey);
        trayScoreboardTag = "farmersdelight:auto_tray:" + trayMarkerKey.getKey();
    }

    private NamespacedKey createTrayMarkerKey(String markerKey) {
        String key = markerKey == null || markerKey.isBlank() ? DEFAULT_MARKER_KEY : markerKey.trim();
        try {
            return new NamespacedKey(plugin, key);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning(I18n.formatConsole("tray.invalid_marker_key", "key", key));
            return defaultTrayMarkerKey;
        }
    }

    public void reload() {
        boolean wasEnabled = enabled;
        double oldXOffset = xOffset;
        double oldYOffset = yOffset;
        double oldZOffset = zOffset;

        loadConfig();
        if (!enabled) {
            stop();
            removeAllTrays();
            return;
        }

        clearPendingSyncs();
        resetSyncCursors();
        start();

        // When the offset changes, purge existing trays at the old offset positions before the resync replaces them.
        if (wasEnabled && (oldXOffset != xOffset || oldYOffset != yOffset || oldZOffset != zOffset)) {
            purgeTraysAtOffset(oldXOffset, oldYOffset, oldZOffset);
        }

        syncAllTraysNow();
    }

    private void purgeTraysAtOffset(double offsetX, double offsetY, double offsetZ) {
        java.util.Set<Location> owners = new java.util.LinkedHashSet<>();
        collectOwnerLocations(owners, CookingPotBlockBehavior.getBlockEntityLocations());
        collectOwnerLocations(owners, getSkilletLocations());

        for (Location owner : owners) {
            World world = owner.getWorld();
            BlockPos ownerPos = new BlockPos(owner.getBlockX(), owner.getBlockY(), owner.getBlockZ());
            Location oldTrayLoc = new Location(world,
                    ownerPos.x() + offsetX, ownerPos.y() + offsetY, ownerPos.z() + offsetZ);
            BlockPos oldTrayPos = new BlockPos(
                    oldTrayLoc.getBlockX(), oldTrayLoc.getBlockY(), oldTrayLoc.getBlockZ());
            // Schedule at the old tray location so the entity scan runs on the region that owns it.
            plugin.scheduler().runAt(oldTrayLoc, () -> removeTrayAt(world, oldTrayPos, "offset changed"));
        }
    }

    private void collectOwnerLocations(java.util.Set<Location> target, Collection<Location> source) {
        if (source == null) {
            return;
        }
        for (Location location : source) {
            if (location != null && location.getWorld() != null) {
                target.add(location);
            }
        }
    }

    private void start() {
        stop();
        if (!enabled) {
            return;
        }
        syncTask = plugin.scheduler().runRepeating(this::syncAllTraysBatched, 1L, syncIntervalTicks);
    }

    public void stop() {
        if (startupCleanupTask != null) {
            startupCleanupTask.cancel();
            startupCleanupTask = null;
        }
        if (syncTask != null) {
            syncTask.cancel();
            syncTask = null;
        }
        synchronized (queuedSyncTaskLock) {
            if (queuedSyncTask != null) {
                queuedSyncTask.cancel();
                queuedSyncTask = null;
            }
        }
    }

    private void scheduleStartupCleanup() {
        if (startupCleanupTask != null && !startupCleanupTask.isCancelled()) {
            return;
        }
        startupCleanupTask = plugin.scheduler().runLater(() -> {
            startupCleanupTask = null;
            runStartupCleanup();
        }, STARTUP_CLEANUP_DELAY_TICKS);
    }

    private void runStartupCleanup() {
        if (!enabled) {
            removeAllTrays();
            return;
        }
        cleanupInvalidAutoTrays();
    }

    private void clearPendingSyncs() {
        scheduledTraySyncs.clear();
        queuedTraySyncs.clear();
        queuedTraySyncKeys.clear();
    }

    private void resetSyncCursors() {
        cookingPotSyncCursor = 0;
        skilletSyncCursor = 0;
    }

    public void checkAndPlaceTray(World world, BlockPos potPos) {
        if (!enabled || world == null || potPos == null) return;
        trackWorld(world);

        // A tray only belongs under an actual cooking pot / skillet. shouldHaveTray only checks the heat
        // source below, so without this guard placing a campfire (a heat source) would spuriously place a
        // tray in the empty block above it — and every block place/break near a heat source queues a sync
        // here via syncAroundSupportChange, so that spurious CraftEngineFurniture.place ran on the hot
        // path. Verifying the owner block first short-circuits before the entity scan + place.
        if (!isPotOrSkilletAt(world, potPos)) {
            removeTrayIfAutoPlaced(world, potPos);
            return;
        }

        // 获取 chunk 实体列表，避免 Folia 区域线程上调用 getNearbyEntities
        List<Entity> potChunkEntities = getChunkEntities(world, potPos);
        if (!shouldHaveTray(world, potPos, potChunkEntities)) {
            removeTrayIfAutoPlaced(world, potPos);
            return;
        }

        Location trayLoc = getTrayLocation(world, potPos);
        // getMaxHeight() is exclusive (the highest placeable Y is getMaxHeight() - 1).
        if (trayLoc.getY() < world.getMinHeight() || trayLoc.getY() >= world.getMaxHeight()) {
            return;
        }

        BlockPos trayPos = new BlockPos(trayLoc.getBlockX(), trayLoc.getBlockY(), trayLoc.getBlockZ());
        List<Entity> trayChunkEntities = getChunkEntities(world, trayLoc);
        List<ItemDisplay> existingTrayEntities = findTrayItemDisplays(world, trayLoc, trayChunkEntities);
        ItemDisplay existingAutoTray = firstAutoTrayEntity(existingTrayEntities);
        if (existingAutoTray != null) {
            markTrayEntity(existingAutoTray, world, potPos);
            removeDuplicateAutoTrays(world, trayPos, existingTrayEntities, existingAutoTray, "duplicate auto tray before place");
            return;
        }

        if (!existingTrayEntities.isEmpty()) {
            for (ItemDisplay existing : existingTrayEntities) {
                if (isManualTray(existing)) {
                    // A player placed this tray; never reclaim/own it. Its presence already satisfies the pot's
                    // tray visual, so don't stack an auto tray on top either — just leave it alone.
                    return;
                }
            }
            // An unmarked tray furniture already exists at the auto-tray position (auto PDC markers lost when
            // CraftEngine rebuilt the display entity on restart/chunk reload). Reclaim it (re-mark + track) so
            // break-removal and break-protection logic can recognize it again.
            ItemDisplay reclaimed = existingTrayEntities.get(0);
            markTrayEntity(reclaimed, world, potPos);
            removeDuplicateAutoTrays(world, trayPos, existingTrayEntities, reclaimed, "reclaim unmarked tray");
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

            markTrayFurniture(furniture, world, potPos);

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

        Location trayLoc = getTrayLocation(world, potPos);
        BlockPos trayPos = new BlockPos(trayLoc.getBlockX(), trayLoc.getBlockY(), trayLoc.getBlockZ());
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

        // Trays can only exist under cooking pots/skillets. If no cooking pots or skillets are tracked anywhere,
        // there can be no auto trays, so skip to avoid scheduling a wasted region task on every normal block break. Both checks are cheap (no list allocation).
        SkilletManager skilletManager = plugin.getSkilletManager();
        boolean anySkillets = skilletManager != null && skilletManager.hasTrackedSkillets();
        if (!CookingPotBlockBehavior.hasAnyBlockEntities() && !anySkillets) {
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
        synchronized (queuedSyncTaskLock) {
            if (queuedSyncTask != null) {
                return;
            }
            // Period 4L rather than 1L: the tray is a visual decoration, so a ~200ms delay is imperceptible,
            // and a batch budget of 32 is enough to drain the queue. A 1L period would make the
            // stopQueuedSyncTaskIfIdle check at the end of every run enter the synchronized block, so 4L cuts that overhead by 75%.
            queuedSyncTask = plugin.scheduler().runRepeating(this::processQueuedTraySyncs, 1L, 4L);
        }
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
        synchronized (queuedSyncTaskLock) {
            if (!queuedTraySyncs.isEmpty() || queuedSyncTask == null) {
                return;
            }
            queuedSyncTask.cancel();
            queuedSyncTask = null;
        }
    }

    private boolean isPotOrSkilletAt(World world, BlockPos pos) {
        if (CookingPotBlockBehavior.isCookingPotBlock(world, new BlockPosKey(pos))) {
            return true;
        }
        Location location = new Location(world, pos.x(), pos.y(), pos.z());
        return isSkilletBlock(location);
    }

    public boolean shouldHaveTray(World world, BlockPos potPos) {
        return shouldHaveTray(world, potPos, null);
    }

    /**
     * 与 shouldHaveTray(World, BlockPos) 相同，但使用预获取的实体列表检查手柄，
     * 避免在 Folia 区域线程上调用 getNearbyEntities。
     * entities 为 null 时退回到普通的 hasHandle 调用。
     */
    public boolean shouldHaveTray(World world, BlockPos potPos, @Nullable List<Entity> entities) {
        HeatSourceConfig heatConfig = plugin.getHeatSourceConfig();
        if (heatConfig == null) {
            return false;
        }

        // Handle takes precedence: a pot with a player-installed handle never shows a tray.
        HandleManager hm = plugin.getHandleManager();
        if (hm != null) {
            boolean hasHandle = entities != null
                    ? hm.hasHandle(world, potPos, entities)
                    : hm.hasHandle(world, potPos);
            if (hasHandle) {
                return false;
            }
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

    private Collection<Location> getSkilletLocations() {
        SkilletManager skilletManager = plugin.getSkilletManager();
        return skilletManager == null ? List.of() : skilletManager.getTrackedLocations();
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

        skilletSyncCursor = scheduleBatchedTraySyncs(
                getSkilletLocations(),
                skilletSyncCursor,
                true,
                scheduledThisRun
        );
        // Orphaned trays (owner pot removed while the chunk was unloaded) are reconciled by the chunk-load
        // path (ChunkLoadListener -> cleanupInvalidAutoTraysIn). The old periodic world-wide
        // ItemDisplay scan that used to live here has been removed — it stalled the main thread on
        // worlds with many displays for no extra coverage.
    }

    private void syncAllTraysNow() {
        if (!enabled) {
            return;
        }

        Set<TraySyncKey> scheduledThisRun = new HashSet<>();
        scheduleBatchedTraySyncs(CookingPotBlockBehavior.getBlockEntityLocations(), 0, false, scheduledThisRun);
        scheduleBatchedTraySyncs(getSkilletLocations(), 0, false, scheduledThisRun);
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

        // The source methods always return a freshly built private ArrayList (not a live backing reference; an empty set is List.of() and already short-circuited by isEmpty() above),
        // so when rawLocations is both a List and RandomAccess, index it directly and skip a full copy;
        // otherwise fall back to a copy.
        List<Location> locations = (rawLocations instanceof List<Location> list && rawLocations instanceof RandomAccess)
                ? list
                : new ArrayList<>(rawLocations);
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

    private boolean scheduleTraySync(Location location) {
        return scheduleTraySync(location, null);
    }

    private boolean scheduleTraySync(Location location, @Nullable Set<TraySyncKey> scheduledThisRun) {
        if (location == null || location.getWorld() == null) {
            return false;
        }

        TraySyncKey key = new TraySyncKey(
                location.getWorld().getUID(),
                location.getBlockX(),
                location.getBlockY(),
                location.getBlockZ()
        );
        if (scheduledThisRun != null && !scheduledThisRun.add(key)) {
            return false;
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
            return true;
        }

        if (!scheduledTraySyncs.add(key)) {
            return false;
        }
        try {
            plugin.scheduler().runAt(normalized, () -> {
                try {
                    syncTrayOwner(normalized);
                } finally {
                    scheduledTraySyncs.remove(key);
                }
            });
            return true;
        } catch (RuntimeException e) {
            scheduledTraySyncs.remove(key);
            return false;
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
            HandleManager hm = plugin.getHandleManager();
            if (hm != null) hm.removeHandle(world, ownerPos);
            return;
        }

        List<Entity> chunkEntities = getChunkEntities(world, ownerPos);
        if (shouldHaveTray(world, ownerPos, chunkEntities)) {
            checkAndPlaceTray(world, ownerPos);
        } else {
            removeTrayIfAutoPlaced(world, ownerPos);
        }
    }

    private List<Location> getKnownTrayOwnerLocations(@Nullable UUID worldId) {
        List<Location> locations = new ArrayList<>();
        addKnownTrayOwnerLocations(locations, CookingPotBlockBehavior.getBlockEntityLocations(), worldId);
        addKnownTrayOwnerLocations(locations, getSkilletLocations(), worldId);
        return locations;
    }

    private void addKnownTrayOwnerLocations(
            List<Location> target,
            Collection<Location> source,
            @Nullable UUID worldId
    ) {
        if (source == null || source.isEmpty()) {
            return;
        }
        for (Location location : source) {
            if (location == null || location.getWorld() == null) {
                continue;
            }
            if (worldId != null && !location.getWorld().getUID().equals(worldId)) {
                continue;
            }
            target.add(new Location(
                    location.getWorld(),
                    location.getBlockX(),
                    location.getBlockY(),
                    location.getBlockZ()
            ));
        }
    }

    private int scheduleKnownOwnerTraySyncs(@Nullable UUID worldId) {
        Set<TraySyncKey> scheduledThisRun = new HashSet<>();
        int scheduled = 0;
        for (Location location : getKnownTrayOwnerLocations(worldId)) {
            if (scheduleTraySync(location, scheduledThisRun)) {
                scheduled++;
            }
        }
        return scheduled;
    }

    private void removeKnownOwnerTrays(@Nullable UUID worldId, String reason) {
        Set<TraySyncKey> seen = new HashSet<>();
        for (Location location : getKnownTrayOwnerLocations(worldId)) {
            if (location == null || location.getWorld() == null) {
                continue;
            }
            TraySyncKey key = new TraySyncKey(
                    location.getWorld().getUID(),
                    location.getBlockX(),
                    location.getBlockY(),
                    location.getBlockZ()
            );
            if (!seen.add(key)) {
                continue;
            }

            World world = location.getWorld();
            BlockPos ownerPos = new BlockPos(key.x(), key.y(), key.z());
            Location trayLoc = getTrayLocation(world, ownerPos);
            BlockPos trayPos = new BlockPos(
                    trayLoc.getBlockX(),
                    trayLoc.getBlockY(),
                    trayLoc.getBlockZ()
            );
            scheduleRemoveTrayAt(world, trayPos, reason);
        }
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

    private void markTrayFurniture(BukkitFurniture furniture, World ownerWorld, BlockPos ownerPos) {
        Entity entity = furniture.bukkitEntity();
        markTrayEntity(entity, ownerWorld, ownerPos);
    }

    private void markTrayEntity(Entity entity, World ownerWorld, BlockPos ownerPos) {
        if (entity == null || !entity.isValid() || ownerWorld == null || ownerPos == null) {
            return;
        }
        entity.getPersistentDataContainer().set(trayMarkerKey, PersistentDataType.BYTE, (byte) 1);
        entity.getPersistentDataContainer().set(defaultTrayMarkerKey, PersistentDataType.BYTE, (byte) 1);
        entity.getPersistentDataContainer().set(trayOwnerWorldKey, PersistentDataType.STRING, ownerWorld.getUID().toString());
        entity.getPersistentDataContainer().set(trayOwnerXKey, PersistentDataType.INTEGER, ownerPos.x());
        entity.getPersistentDataContainer().set(trayOwnerYKey, PersistentDataType.INTEGER, ownerPos.y());
        entity.getPersistentDataContainer().set(trayOwnerZKey, PersistentDataType.INTEGER, ownerPos.z());
        entity.addScoreboardTag(trayScoreboardTag);
        removeScoreboardTagsWithPrefix(entity, OWNER_SCOREBOARD_PREFIX);
        entity.addScoreboardTag(ownerScoreboardTag(ownerWorld, ownerPos));
    }

    public boolean isAutoPlacedTray(BukkitFurniture furniture) {
        if (furniture == null) {
            return false;
        }
        Entity entity = furniture.bukkitEntity();
        return isAutoPlacedTrayEntity(entity);
    }

    /** Marks a player-placed tray so it is never treated as an auto tray. CraftEngine fires FurniturePlaceEvent
     * only for player placement (the auto tray is placed programmatically and does not), cleanly distinguishing
     * the two. No-op for non-tray furniture. */
    public void markManualTrayFurniture(BukkitFurniture furniture) {
        if (furniture == null || !furniture.id().toString().equals(trayFurnitureId)) {
            return;
        }
        Entity entity = furniture.bukkitEntity();
        if (entity == null || !entity.isValid()) {
            return;
        }
        entity.getPersistentDataContainer().set(manualTrayMarkerKey, PersistentDataType.BYTE, (byte) 1);
        entity.addScoreboardTag(MANUAL_TRAY_SCOREBOARD_TAG);
    }

    /** True when the tray was placed by a player, so it must never be auto-reclaimed, absorbed, or removed. */
    private boolean isManualTray(Entity entity) {
        if (entity == null) {
            return false;
        }
        return entity.getPersistentDataContainer().has(manualTrayMarkerKey, PersistentDataType.BYTE)
                || entity.getScoreboardTags().contains(MANUAL_TRAY_SCOREBOARD_TAG);
    }

    private boolean isAutoPlacedTrayEntity(Entity entity) {
        if (entity == null || !entity.isValid()) {
            return false;
        }
        // A player-placed tray is never an auto tray, even if it sits at the auto-tray position.
        if (isManualTray(entity)) {
            return false;
        }
        if (entity.getPersistentDataContainer().has(trayMarkerKey, PersistentDataType.BYTE)
                || entity.getPersistentDataContainer().has(defaultTrayMarkerKey, PersistentDataType.BYTE)) {
            return true;
        }
        for (String tag : entity.getScoreboardTags()) {
            if (tag.equals(trayScoreboardTag) || tag.startsWith(MARKER_SCOREBOARD_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private TrayOwner getTrayOwner(Entity entity) {
        if (entity == null || !entity.isValid()) {
            return null;
        }
        String worldId = entity.getPersistentDataContainer().get(trayOwnerWorldKey, PersistentDataType.STRING);
        Integer x = entity.getPersistentDataContainer().get(trayOwnerXKey, PersistentDataType.INTEGER);
        Integer y = entity.getPersistentDataContainer().get(trayOwnerYKey, PersistentDataType.INTEGER);
        Integer z = entity.getPersistentDataContainer().get(trayOwnerZKey, PersistentDataType.INTEGER);
        if (worldId != null && x != null && y != null && z != null) {
            try {
                return new TrayOwner(UUID.fromString(worldId), new BlockPos(x, y, z));
            } catch (IllegalArgumentException ignored) {
            }
        }

        for (String tag : entity.getScoreboardTags()) {
            if (!tag.startsWith(OWNER_SCOREBOARD_PREFIX)) {
                continue;
            }
            TrayOwner owner = parseOwnerScoreboardTag(tag);
            if (owner != null) {
                return owner;
            }
        }
        return null;
    }

    @Nullable
    private TrayOwner parseOwnerScoreboardTag(String tag) {
        String payload = tag.substring(OWNER_SCOREBOARD_PREFIX.length());
        String[] parts = payload.split(":");
        if (parts.length != 4) {
            return null;
        }
        try {
            return new TrayOwner(
                    UUID.fromString(parts[0]),
                    new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]))
            );
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String ownerScoreboardTag(World world, BlockPos ownerPos) {
        return OWNER_SCOREBOARD_PREFIX + world.getUID() + ":" + ownerPos.x() + ":" + ownerPos.y() + ":" + ownerPos.z();
    }

    private void removeScoreboardTagsWithPrefix(Entity entity, String prefix) {
        for (String tag : List.copyOf(entity.getScoreboardTags())) {
            if (tag.startsWith(prefix)) {
                entity.removeScoreboardTag(tag);
            }
        }
    }

    @Nullable
    private TrayOwner resolveTrayOwner(World world, Entity entity) {
        TrayOwner owner = getTrayOwner(entity);
        if (owner != null) {
            return owner;
        }
        if (world == null || entity == null || !entity.isValid()) {
            return null;
        }
        return new TrayOwner(world.getUID(), inferOwnerPos(entity.getLocation()));
    }

    private BlockPos inferOwnerPos(Location trayLocation) {
        return new BlockPos(
                (int) Math.floor(trayLocation.getX() - xOffset + 1.0E-6D),
                (int) Math.floor(trayLocation.getY() - yOffset + 1.0E-6D),
                (int) Math.floor(trayLocation.getZ() - zOffset + 1.0E-6D)
        );
    }

    public int cleanupInvalidAutoTrays() {
        // Folia and Paper now share the known-owner path: ChunkLoadListener already runs
        // cleanupInvalidAutoTraysIn per chunk on load (covers tray entities), and this call
        // re-syncs every tracked pot/skillet owner so any auto-tray whose owner state changed gets
        // placed/removed. The previous world-wide ItemDisplay scan duplicated chunk-load work and
        // blocked the main thread proportionally to the world's total display count.
        return scheduleKnownOwnerTraySyncs(null);
    }

    public void cleanupInvalidAutoTraysInChunk(World world, int chunkX, int chunkZ) {
        if (world == null) {
            return;
        }
        if (plugin.scheduler().isFolia()) {
            plugin.scheduler().runAt(world, chunkX, chunkZ, () -> cleanupInvalidAutoTraysInChunkNow(world, chunkX, chunkZ));
            return;
        }
        cleanupInvalidAutoTraysInChunkNow(world, chunkX, chunkZ);
    }

    private void cleanupInvalidAutoTraysInChunkNow(World world, int chunkX, int chunkZ) {
        if (world == null || !world.isChunkLoaded(chunkX, chunkZ)) {
            return;
        }

        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        cleanupInvalidAutoTraysIn(world, Arrays.asList(chunk.getEntities()));
    }

    /**
     * Sweeps an already-fetched entity list instead of fetching a chunk's own. Lets a caller that has to
     * walk the same chunk's entities for another purpose share one walk; the caller is responsible for
     * having confirmed the chunk is loaded and for being on the region that owns it.
     */
    public void cleanupInvalidAutoTraysIn(World world, List<Entity> entities) {
        if (world == null || entities == null) {
            return;
        }
        for (Entity entity : entities) {
            if (!(entity instanceof ItemDisplay itemDisplay)
                    || !isTrayFurnitureEntity(itemDisplay)
                    || !isAutoPlacedTrayEntity(itemDisplay)) {
                continue;
            }

            TrayOwner owner = resolveTrayOwner(world, itemDisplay);
            if (enabled
                    && owner != null
                    && owner.worldId().equals(world.getUID())
                    && isValidAutoTrayOwner(world, owner.pos(), entities)) {
                markTrayEntity(itemDisplay, world, owner.pos());
                continue;
            }

            removeTrayEntity(itemDisplay, new HashSet<>());
        }
    }

    private boolean isValidAutoTrayOwner(World world, BlockPos ownerPos) {
        return isValidAutoTrayOwner(world, ownerPos, null);
    }

    private boolean isValidAutoTrayOwner(World world, BlockPos ownerPos, @Nullable List<Entity> entities) {
        return ownerPos != null && isPotOrSkilletAt(world, ownerPos) && shouldHaveTray(world, ownerPos, entities);
    }

    private void removeTrayAt(World world, BlockPos trayPos, String reason) {
        try {
            Location location = new Location(world, trayPos.x(), trayPos.y(), trayPos.z());
            List<Entity> chunkEntities = getChunkEntities(world, location);
            int removed = 0;
            boolean allowUnmarkedExactTray = "owner removed".equals(reason)
                    || "cleanup invalid owner".equals(reason)
                    || "offset changed".equals(reason);
            Set<UUID> removedEntities = new HashSet<>();
            for (BukkitFurniture furniture : findTrayFurnitures(world, location, chunkEntities)) {
                if (isManualTray(furniture.bukkitEntity())) {
                    // Never remove a player-placed tray, even at the exact auto-tray position.
                    continue;
                }
                if (!isAutoPlacedTray(furniture)) {
                    // Owner-specific cleanup still reclaims old, unmarked tray furniture at the exact auto-tray position.
                    // The global cleanup path only scans marked trays.
                    if (!allowUnmarkedExactTray) {
                        continue;
                    }
                }
                Entity entity = furniture.bukkitEntity();
                if (removeTrayEntity(entity, removedEntities)) {
                    removed++;
                }
            }

            for (ItemDisplay entity : findTrayItemDisplays(world, location, chunkEntities)) {
                if (isManualTray(entity)) {
                    continue;
                }
                if (!isAutoPlacedTrayEntity(entity) && !allowUnmarkedExactTray) {
                    continue;
                }
                if (removeTrayEntity(entity, removedEntities)) {
                    removed++;
                }
            }

            if (removed > 0 && plugin.isDebugEnabled("tray")) {
                plugin.getLogger().info(I18n.formatConsole("tray.removed", "pos", trayPos, "reason", reason));
            }
        } catch (Exception e) {
            plugin.getLogger().warning(I18n.formatConsole("tray.remove_failed", "error", e.getMessage()));
        }
    }

    private boolean removeTrayEntity(Entity entity, Set<UUID> removedEntities) {
        if (entity == null || !entity.isValid()) {
            return false;
        }
        if (removedEntities != null && !removedEntities.add(entity.getUniqueId())) {
            return false;
        }
        try {
            if (CraftEngineFurniture.remove(entity, false, false)) {
                return true;
            }
        } catch (Exception ignored) {
        }
        if (entity.isValid()) {
            entity.remove();
            return true;
        }
        return false;
    }

    private List<BukkitFurniture> findTrayFurnitures(World world, Location location) {
        return findTrayFurnitures(world, location, getChunkEntities(world, location));
    }

    private List<BukkitFurniture> findTrayFurnitures(World world, Location location, List<Entity> entities) {
        if (world == null || location == null || entities == null) {
            return List.of();
        }
        List<BukkitFurniture> furnitures = new ArrayList<>();
        Set<UUID> seenEntities = new HashSet<>();
        for (ItemDisplay entity : findTrayItemDisplays(world, location, entities)) {
            BukkitFurniture furniture = getLoadedTrayFurniture(entity);
            Entity rootEntity = furniture == null ? null : furniture.bukkitEntity();
            if (rootEntity != null && seenEntities.add(rootEntity.getUniqueId())) {
                furnitures.add(furniture);
            }
        }
        return furnitures;
    }

    private List<ItemDisplay> findTrayItemDisplays(World world, Location location) {
        return findTrayItemDisplays(world, location, getChunkEntities(world, location));
    }

    /**
     * 使用预获取的实体列表查找托盘 ItemDisplay，避免在 Folia 区域线程上调用 getNearbyEntities。
     */
    private List<ItemDisplay> findTrayItemDisplays(World world, Location location, List<Entity> entities) {
        if (world == null || location == null || entities == null) {
            return List.of();
        }
        List<ItemDisplay> result = new ArrayList<>();
        double bx = location.getBlockX();
        double by = location.getBlockY();
        double bz = location.getBlockZ();
        double margin = 0.3D;
        double minX = bx - margin;
        double minY = by - margin;
        double minZ = bz - margin;
        double maxX = bx + 1 + margin;
        double maxY = by + 1 + margin;
        double maxZ = bz + 1 + margin;
        for (Entity entity : entities) {
            if (!(entity instanceof ItemDisplay itemDisplay)) continue;
            if (!isTrayFurnitureEntity(itemDisplay)) continue;
            Location eloc = entity.getLocation();
            if (eloc.getX() < minX || eloc.getX() > maxX
                    || eloc.getY() < minY || eloc.getY() > maxY
                    || eloc.getZ() < minZ || eloc.getZ() > maxZ) continue;
            result.add(itemDisplay);
        }
        return result;
    }

    @Nullable
    private BukkitFurniture getLoadedTrayFurniture(Entity entity) {
        if (!(entity instanceof ItemDisplay)) {
            return null;
        }
        BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(entity);
        if (furniture == null || !furniture.id().toString().equals(trayFurnitureId)) {
            return null;
        }
        return furniture;
    }

    private boolean isTrayFurnitureEntity(Entity entity) {
        if (!(entity instanceof ItemDisplay) || entity == null || !entity.isValid()) {
            return false;
        }
        BukkitFurniture furniture = getLoadedTrayFurniture(entity);
        if (furniture != null) {
            return true;
        }
        String furnitureId = entity.getPersistentDataContainer().get(
                BukkitFurnitureManager.FURNITURE_KEY,
                PersistentDataType.STRING
        );
        return trayFurnitureId.equals(furnitureId);
    }

    @Nullable
    private ItemDisplay firstAutoTrayEntity(Collection<ItemDisplay> entities) {
        if (entities == null || entities.isEmpty()) {
            return null;
        }
        for (ItemDisplay entity : entities) {
            if (isAutoPlacedTrayEntity(entity)) {
                return entity;
            }
        }
        return null;
    }

    private void removeDuplicateAutoTrays(World world, BlockPos trayPos, Collection<ItemDisplay> entities,
                                          ItemDisplay keep, String reason) {
        if (entities == null || entities.isEmpty()) {
            return;
        }
        Set<UUID> removedEntities = new HashSet<>();
        if (keep != null) {
            removedEntities.add(keep.getUniqueId());
        }
        for (ItemDisplay entity : entities) {
            if (entity == keep || !isAutoPlacedTrayEntity(entity)) {
                continue;
            }
            removeTrayEntity(entity, removedEntities);
        }
        if (plugin.isDebugEnabled("tray")) {
            plugin.getLogger().info(I18n.formatConsole("tray.duplicates_cleaned", "pos", trayPos, "reason", reason));
        }
    }

    public void cleanupWorld(UUID worldId) {
        World world = knownWorlds.remove(worldId);
        if (world == null) {
            world = Bukkit.getWorld(worldId);
        }
        scheduledTraySyncs.removeIf(key -> key.worldId().equals(worldId));
        queuedTraySyncKeys.removeIf(key -> key.worldId().equals(worldId));
        queuedTraySyncs.removeIf(location -> location != null
                && location.getWorld() != null
                && location.getWorld().getUID().equals(worldId));
        stopQueuedSyncTaskIfIdle();
        if (world != null) {
            removeKnownOwnerTrays(worldId, "world cleanup");
        }
    }

    private void removeAllTrays() {
        removeKnownOwnerTrays(null, "remove all trays");
        clearPendingSyncs();
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

    /** 从 BlockPos 所在 chunk 获取实体列表，chunk 未加载时返回空列表。 */
    private List<Entity> getChunkEntities(World world, BlockPos pos) {
        if (world == null || pos == null) return List.of();
        int cx = pos.x() >> 4;
        int cz = pos.z() >> 4;
        if (!world.isChunkLoaded(cx, cz)) return List.of();
        return java.util.Arrays.asList(world.getChunkAt(cx, cz).getEntities());
    }

    /** 从 Location 所在 chunk 获取实体列表，chunk 未加载时返回空列表。 */
    private List<Entity> getChunkEntities(World world, Location loc) {
        if (world == null || loc == null) return List.of();
        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        if (!world.isChunkLoaded(cx, cz)) return List.of();
        return java.util.Arrays.asList(world.getChunkAt(cx, cz).getEntities());
    }

    private record TraySyncKey(UUID worldId, int x, int y, int z) {
    }

    private record TrayOwner(UUID worldId, BlockPos pos) {
    }
}
