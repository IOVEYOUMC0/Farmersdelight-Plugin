package com.huidu.farmersdelight.visual;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.config.ConfigSectionReader;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.ManagerSupport;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import net.kyori.adventure.text.Component;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.bukkit.plugin.network.BukkitNetworkManager;
import net.momirealms.craftengine.core.plugin.network.NetWorkUser;
import net.momirealms.craftengine.bukkit.util.EntityUtils;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

public class ProxyItemDisplayManager implements ItemDisplayManager {

    private static final double DEFAULT_VIEW_DISTANCE = 64.0D;
    private static final int DEFAULT_SYNC_INTERVAL_TICKS = 20;
    private static final int DEFAULT_SYNC_BATCH_SIZE = 256;
    // Extra blocks an already-visible viewer keeps, so a player standing on the send boundary does not
    // flap between spawn and destroy on every pass. Shared policy lives in DisplayCulling.
    private static final double VIEW_HYSTERESIS = 8.0D;

    private final FarmersDelightPlugin plugin;
    private final ProxyDisplayPacketFactory packets;
    private final BukkitNetworkManager networkManager;
    private final Map<Integer, ProxyDisplay> displays = new ConcurrentHashMap<>();
    // Index of display ids by (world, chunk) so unload and player-centric visibility queries touch only
    // relevant displays. Addon API updates can move a display, so updateDisplay re-indexes atomically.
    private final Map<UUID, Map<Long, Set<Integer>>> displaysByChunk = new ConcurrentHashMap<>();
    private final Map<UUID, Player> onlinePlayers = new ConcurrentHashMap<>();
    // Reverse viewer index keeps player-centric Folia sync and quit/world cleanup O(visible displays)
    // instead of scanning every proxy display on the server.
    private final Map<UUID, Set<Integer>> visibleDisplaysByPlayer = new ConcurrentHashMap<>();
    private final AtomicLong displaySnapshotVersion = new AtomicLong();
    private volatile List<ProxyDisplay> displaySnapshot = List.of();
    private volatile long displaySnapshotCachedVersion = -1L;
    // Region ticks may start the display sync task while reload or cleanup cancels it globally.
    // Volatile publishes the handle; the lock prevents concurrent callers from starting duplicate tasks.
    private volatile PluginTask syncTask;
    private final Object syncTaskLock = new Object();
    private volatile double viewDistance = DEFAULT_VIEW_DISTANCE;
    private volatile double viewDistanceSquared = DEFAULT_VIEW_DISTANCE * DEFAULT_VIEW_DISTANCE;
    // Display ViewRange metadata: the client renders the display within ~ViewRange × 64 blocks. Derived
    // from the send distance so the client's render cutoff tracks the server's send cutoff — otherwise a
    // raised view-distance would send displays the client (ViewRange fixed at 1.0 ≈ 64) refuses to draw.
    // Default 64 → 1.0, unchanged. Mirrors CE furniture's viewRange-from-config approach.
    private volatile float viewRangeMeta = 1.0f;
    private int syncIntervalTicks = DEFAULT_SYNC_INTERVAL_TICKS;
    private int syncBatchSize = DEFAULT_SYNC_BATCH_SIZE;
    private int syncCursor;
    // Queue create/update bursts until the next tick so displays in one chunk share a player lookup.
    // Existing viewers receive update packets synchronously; new-in-range synchronization may wait one tick.
    private final Set<ProxyDisplay> pendingSync = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean pendingSyncScheduled = new AtomicBoolean();

    // Debug metrics
    // Counters wired into the create/update/destroy + per-viewer packet paths so a debug build can
    // measure (a) how many real packets actually go out for what business activity and (b) how often
    // the text-diff short-circuit fires. All counters are reset on plugin reload (see reload()).
    private final AtomicLong itemSpawnCount = new AtomicLong();
    private final AtomicLong itemUpdateCount = new AtomicLong();
    private final AtomicLong textSpawnCount = new AtomicLong();
    private final AtomicLong textUpdateCount = new AtomicLong();
    private final AtomicLong textUpdateDiffHitCount = new AtomicLong();
    private final AtomicLong destroyCount = new AtomicLong();
    private final AtomicLong viewerSpawnPacketCount = new AtomicLong();
    private final AtomicLong viewerDestroyPacketCount = new AtomicLong();
    private final AtomicLong viewerUpdatePacketCount = new AtomicLong();
    private final AtomicLong syncRunCount = new AtomicLong();
    private volatile long debugStatsResetEpochMs = System.currentTimeMillis();

    public ProxyItemDisplayManager(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
        this.packets = new ProxyDisplayPacketFactory();
        BukkitCraftEngine craftEngine = BukkitCraftEngine.instance();
        if (craftEngine != null) {
            this.networkManager = craftEngine.networkManager();
        } else {
            this.networkManager = null;
        }
        if (isAvailable()) {
            Bukkit.getPluginManager().registerEvents(new ProxyDisplayPlayerListener(this), plugin);
            for (Player player : Bukkit.getOnlinePlayers()) {
                onlinePlayers.put(player.getUniqueId(), player);
            }
            // reload() arms the sync pass through ensureSyncTask, which is the only path allowed to start it:
            // a second bare start here would overwrite the handle and leave the first task running untracked,
            // beyond the reach of every cancel.
            reload();
        }
    }

    @Override
    public boolean isAvailable() {
        return networkManager != null;
    }

    @Override
    public boolean isActive(int entityId) {
        return displays.containsKey(entityId);
    }

    private int allocateEntityId() {
        int entityId;
        do {
            // CraftEngine exposes Minecraft's actual global entity counter. Reserving IDs from the same
            // allocator prevents packet-only displays from ever colliding with real or CE entities.
            entityId = EntityUtils.ENTITY_COUNTER.incrementAndGet();
        } while (displays.containsKey(entityId));
        return entityId;
    }

    public void reload() {
        viewDistance = Math.max(8.0D, ConfigSectionReader.optionalDouble(
                plugin.getConfig(),
                "performance.proxy-display.view-distance", DEFAULT_VIEW_DISTANCE));
        viewDistanceSquared = viewDistance * viewDistance;
        viewRangeMeta = (float) (viewDistance / 64.0D);
        packets.reload(viewRangeMeta);
        syncIntervalTicks = Math.max(1, ConfigSectionReader.optionalInt(
                plugin.getConfig(),
                "performance.proxy-display.sync-interval-ticks", DEFAULT_SYNC_INTERVAL_TICKS));
        syncBatchSize = Math.max(1, ConfigSectionReader.optionalInt(
                plugin.getConfig(),
                "performance.proxy-display.sync-batch-size", DEFAULT_SYNC_BATCH_SIZE));
        // Restart the sync task so the new interval takes effect.
        synchronized (syncTaskLock) {
            if (syncTask != null) {
                syncTask.cancel();
                syncTask = null;
            }
        }
        ensureSyncTask();
    }

    @Override
    public int createDisplay(DisplaySpec spec) {
        if (!isAvailable() || spec == null || spec.location() == null || spec.location().getWorld() == null) {
            return -1;
        }

        try {
            DisplaySpec normalizedSpec = normalize(spec);
            int entityId = allocateEntityId();
            UUID entityUuid = UUID.randomUUID();
            Object spawnPacket = packets.createItemSpawnPacket(entityId, entityUuid, normalizedSpec);
            Object metadataPacket = packets.createItemMetadataPacket(entityId, normalizedSpec);
            Object destroyPacket = packets.createDestroyPacket(entityId);
            ProxyDisplay display = new ProxyDisplay(
                    entityId,
                    entityUuid,
                    normalizedSpec,
                    null,
                    spawnPacket,
                    metadataPacket,
                    destroyPacket
            );
            displays.put(entityId, display);
            indexDisplay(display);
            markDisplaySnapshotDirty();
            ensureSyncTask();
            queueSync(display);
            itemSpawnCount.incrementAndGet();
            return entityId;
        } catch (RuntimeException | LinkageError t) {
            // A cosmetic item display must never abort the gameplay that spawns it. If building the display's
            // packets fails (e.g. wrapping the item for the metadata packet throws), returning no-display keeps
            // the caller's logic intact — otherwise a cutting board would store the item and spawn no display
            // yet leave the player's hand item unconsumed (a duplication).
            logDisplayBuildFailure("create", spec, t);
            return -1;
        }
    }

    @Override
    public int createTextDisplay(TextDisplaySpec spec) {
        if (!isAvailable() || spec == null || spec.location() == null || spec.location().getWorld() == null) {
            return -1;
        }

        TextDisplaySpec normalizedSpec = normalizeText(spec);
        int entityId = allocateEntityId();
        UUID entityUuid = UUID.randomUUID();
        Object spawnPacket = packets.createTextSpawnPacket(entityId, entityUuid, normalizedSpec);
        Object metadataPacket = packets.createTextMetadataPacket(entityId, normalizedSpec);
        Object destroyPacket = packets.createDestroyPacket(entityId);
        ProxyDisplay display = new ProxyDisplay(
                entityId,
                entityUuid,
                null,
                normalizedSpec,
                spawnPacket,
                metadataPacket,
                destroyPacket
        );
        displays.put(entityId, display);
        indexDisplay(display);
        markDisplaySnapshotDirty();
        ensureSyncTask();
        queueSync(display);
        textSpawnCount.incrementAndGet();
        return entityId;
    }

    @Override
    public boolean updateText(int entityId, Component text) {
        if (!isAvailable() || text == null) {
            return false;
        }
        ProxyDisplay display = displays.get(entityId);
        if (display == null || !display.isText()) {
            return false;
        }
        TextDisplaySpec current = display.textSpec;
        if (current.text().equals(text)) {
            // Skip metadata broadcasts when the text already matches the displayed value.
            textUpdateDiffHitCount.incrementAndGet();
            return true;
        }
        TextDisplaySpec updated = new TextDisplaySpec(
                current.location(), text, current.transformation(),
                current.backgroundColor(), current.shadowed(), current.seeThrough());
        display.textSpec = updated;
        display.metadataPacket = packets.createTextMetadataPacket(entityId, updated);
        // The spawn packet carries only the entity id, uuid, location and type; the text is not one of
        // its inputs and the location is carried over unchanged, so rebuilding it here would produce the
        // same bytes. Only the metadata packet and the pair that references it need refreshing.
        display.spawnPackets = List.of(display.spawnPacket, display.metadataPacket);
        sendUpdateForAllViewers(display, null, display.metadataPacket);
        textUpdateCount.incrementAndGet();
        return true;
    }

    @Override
    public boolean updateDisplay(int entityId, DisplaySpec spec) {
        if (!isAvailable() || spec == null || spec.location() == null || spec.location().getWorld() == null) {
            return false;
        }

        ProxyDisplay display = displays.get(entityId);
        // Reject a handle of the wrong kind, mirroring updateText. Without this an addon passing a
        // text-display handle would overwrite that display's item/text packets with item-display ones,
        // corrupting FD's own progress text displays.
        if (display == null || display.isText()) {
            return false;
        }

        DisplaySpec previousSpec = display.itemSpec;
        DisplaySpec normalizedSpec = normalize(spec);
        // The spawn packet carries only the entity id, uuid, location and type, so it only has to be
        // rebuilt when the display actually moved. FD's item displays are stationary in a slot, so an
        // item or count change reuses the packet the display already holds. The same answer decides
        // whether a position packet is sent, a few lines below.
        boolean samePosition = previousSpec != null
                && sameDisplayPosition(previousSpec.location(), normalizedSpec.location());
        Object newSpawnPacket;
        Object newMetadataPacket;
        try {
            // Build the packets before mutating the display so a build failure leaves the old visual intact
            // and cannot propagate into the caller (see createDisplay).
            newSpawnPacket = samePosition
                    ? display.spawnPacket
                    : packets.createItemSpawnPacket(entityId, display.entityUuid, normalizedSpec);
            newMetadataPacket = packets.createItemMetadataPacket(entityId, normalizedSpec);
        } catch (RuntimeException | LinkageError t) {
            logDisplayBuildFailure("update", spec, t);
            return false;
        }
        // Only send a position (teleport) packet when the display actually moved. FD's item displays are
        // stationary in-slot — a cutting-board carve/count change updates the item + metadata, never
        // x/y/z — so this drops a redundant position packet per update. Mirrors updateText's null-position
        // path; the packet carries only x/y/z (yaw/pitch are always 0, rotation lives in the transform).
        Object positionPacket = samePosition
                ? null
                : packets.createPositionPacket(entityId, normalizedSpec.location());
        synchronized (display) {
            // destroyDisplay removes from the map before taking this monitor. Abort if it won the race;
            // otherwise destroy waits and will de-index the new location after this update completes.
            if (displays.get(entityId) != display) {
                return false;
            }
            if (!sameDisplayChunk(previousSpec.location(), normalizedSpec.location())) {
                unindexDisplayAt(entityId, previousSpec.location());
            }
            display.itemSpec = normalizedSpec;
            display.spawnPacket = newSpawnPacket;
            display.metadataPacket = newMetadataPacket;
            display.spawnPackets = List.of(display.spawnPacket, display.metadataPacket);
            if (!sameDisplayChunk(previousSpec.location(), normalizedSpec.location())) {
                indexDisplayAt(entityId, normalizedSpec.location());
            }
        }
        sendUpdateForAllViewers(display, positionPacket, display.metadataPacket);
        queueSync(display);
        itemUpdateCount.incrementAndGet();
        return true;
    }

    private static boolean sameDisplayPosition(Location a, Location b) {
        return a != null && b != null
                && a.getWorld() == b.getWorld()
                && a.getX() == b.getX()
                && a.getY() == b.getY()
                && a.getZ() == b.getZ();
    }

    static boolean sameDisplayChunk(Location a, Location b) {
        return a != null && b != null && a.getWorld() != null && b.getWorld() != null
                && a.getWorld().getUID().equals(b.getWorld().getUID())
                && (a.getBlockX() >> 4) == (b.getBlockX() >> 4)
                && (a.getBlockZ() >> 4) == (b.getBlockZ() >> 4);
    }

    @Override
    public void destroyDisplay(int entityId) {
        ProxyDisplay removed = displays.remove(entityId);
        if (removed != null) {
            synchronized (removed) {
                unindexDisplay(removed);
            }
            markDisplaySnapshotDirty();
            destroyForAllViewers(removed);
            destroyCount.incrementAndGet();
            stopSyncTaskIfIdle();
        }
    }

    private void indexDisplay(ProxyDisplay display) {
        indexDisplayAt(display.entityId, display.location());
    }

    private void indexDisplayAt(int entityId, Location loc) {
        if (loc.getWorld() == null) {
            return;
        }
        displaysByChunk.computeIfAbsent(loc.getWorld().getUID(), k -> new ConcurrentHashMap<>())
                .computeIfAbsent(ManagerSupport.chunkKey(loc), k -> ConcurrentHashMap.newKeySet())
                .add(entityId);
    }

    private void unindexDisplay(ProxyDisplay display) {
        unindexDisplayAt(display.entityId, display.location());
    }

    private void unindexDisplayAt(int entityId, Location loc) {
        if (loc.getWorld() == null) {
            return;
        }
        Map<Long, Set<Integer>> byChunk = displaysByChunk.get(loc.getWorld().getUID());
        if (byChunk == null) {
            return;
        }
        long chunkKey = ManagerSupport.chunkKey(loc);
        Set<Integer> ids = byChunk.get(chunkKey);
        if (ids != null) {
            ids.remove(entityId);
            if (ids.isEmpty()) {
                byChunk.remove(chunkKey, ids);
            }
        }
    }

    @Override
    public void cleanupWorld(UUID worldId) {
        if (worldId == null) {
            return;
        }

        List<Integer> toRemove = new ArrayList<>();
        for (Map.Entry<Integer, ProxyDisplay> entry : displays.entrySet()) {
            Location location = entry.getValue().location();
            if (location.getWorld() != null && location.getWorld().getUID().equals(worldId)) {
                toRemove.add(entry.getKey());
            }
        }

        for (Integer entityId : toRemove) {
            destroyDisplay(entityId);
        }
        // destroyDisplay empties the world's inner chunk map but leaves the outer per-world entry; drop it
        // so churning uniquely-UUID'd worlds (minigame arenas, dungeon instances) does not leak empty maps.
        displaysByChunk.remove(worldId);
    }

    @Override
    public int cleanupOrphans(Set<Integer> liveIds) {
        // Iterate a snapshot of the current keys: displays created after this starts aren't in the
        // snapshot, so they're never mistaken for orphans (on Purpur the command runs on the same
        // main thread as createDisplay, so there is no interleave at all). Only remove ids no live
        // block owner still references.
        int removed = 0;
        for (Integer entityId : new ArrayList<>(displays.keySet())) {
            if (!liveIds.contains(entityId)) {
                destroyDisplay(entityId);
                removed++;
            }
        }
        return removed;
    }

    @Override
    public void cleanup() {
        synchronized (syncTaskLock) {
            if (syncTask != null) {
                syncTask.cancel();
                syncTask = null;
            }
        }

        int removed = displays.size();
        for (Integer entityId : new ArrayList<>(displays.keySet())) {
            destroyDisplay(entityId);
        }
        displaysByChunk.clear();
        pendingSync.clear();
        visibleDisplaysByPlayer.clear();
        // Re-seed instead of leaving the map empty: cleanup() is also reachable from the /fd cleanup
        // command while the manager keeps running, and this map only refills on join/teleport/respawn
        // events. An empty map would make viewer eviction paths (stale-viewer destroy, update fan-out)
        // silently miss players who were online before the cleanup, leaving ghost displays client-side.
        onlinePlayers.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            onlinePlayers.put(player.getUniqueId(), player);
        }
        displaySnapshot = List.of();
        markDisplaySnapshotDirty();
    }

    /** A joining player is synced after a short delay so their client is past the login burst. */
    void onPlayerJoined(Player player) {
        schedulePlayerSync(player, 5L);
    }

    void onPlayerChangedWorld(Player player) {
        clearViewer(player.getUniqueId());
        schedulePlayerSync(player, 2L);
    }

    void onPlayerTeleported(Player player) {
        schedulePlayerSync(player, 2L);
    }

    void onPlayerRespawned(Player player) {
        clearViewer(player.getUniqueId());
        schedulePlayerSync(player, 2L);
    }

    void onPlayerQuit(Player player) {
        onlinePlayers.remove(player.getUniqueId());
        clearViewer(player.getUniqueId());
    }

    void onChunkUnloaded(World world, Chunk chunk) {
        Map<Long, Set<Integer>> byChunk = displaysByChunk.get(world.getUID());
        if (byChunk == null) {
            return;
        }
        long chunkKey = ManagerSupport.chunkKey(chunk.getX(), chunk.getZ());
        Set<Integer> ids = byChunk.get(chunkKey);
        if (ids == null || ids.isEmpty()) {
            return;
        }
        // Snapshot before destroying — destroyDisplay mutates this same set via unindexDisplay.
        for (Integer entityId : new ArrayList<>(ids)) {
            destroyDisplay(entityId);
        }
    }

    private void schedulePlayerSync(Player player, long delayTicks) {
        onlinePlayers.put(player.getUniqueId(), player);
        plugin.scheduler().runLaterForEntity(player, () -> syncPlayer(player), delayTicks);
    }

    private void startSyncTask() {
        syncTask = plugin.scheduler().runRepeating(this::syncAll, syncIntervalTicks, syncIntervalTicks);
    }

    private void ensureSyncTask() {
        synchronized (syncTaskLock) {
            if (syncTask == null) {
                startSyncTask();
            }
        }
    }

    private void stopSyncTaskIfIdle() {
        if (!displays.isEmpty()) {
            return;
        }
        synchronized (syncTaskLock) {
            if (!displays.isEmpty() || syncTask == null) {
                return;
            }
            syncTask.cancel();
            syncTask = null;
        }
    }

    private void syncAll() {
        if (!isAvailable()) {
            return;
        }
        syncRunCount.incrementAndGet();
        if (displays.isEmpty()) {
            // Self-cancel at the same point as the pass's early-return guard, not at the tail: the pass
            // returns here on every drain-to-empty (destroy, chunk unload, world cleanup), so putting the
            // check anywhere later would leave those paths spinning the task forever.
            stopSyncTaskIfIdle();
            return;
        }

        List<ProxyDisplay> snapshot = getDisplaySnapshot();
        int size = snapshot.size();
        if (size == 0) {
            syncCursor = 0;
            return;
        }

        int budget = Math.min(syncBatchSize, size);
        int start = syncCursor >= size ? 0 : syncCursor;
        int processed = 0;
        // Same-chunk displays (4 stove slots, cutting board + text) share one chunk-player lookup for
        // the whole batch. The batch runs synchronously on the main thread, so chunk player-tracking
        // cannot change mid-pass and the memo is exactly equivalent to per-display fresh calls. On Folia
        // each display is handed to its own region instead, and carries a memo private to that task.
        Map<ChunkKey, Collection<Player>> memo = plugin.scheduler().isFolia() ? null : new HashMap<>();
        for (int i = 0; i < budget; i++) {
            ProxyDisplay display = snapshot.get((start + i) % size);
            scheduleSyncDisplay(display, memo);
            processed++;
        }
        syncCursor = (start + Math.max(1, processed)) % size;
    }

    private void markDisplaySnapshotDirty() {
        displaySnapshotVersion.incrementAndGet();
    }

    private List<ProxyDisplay> getDisplaySnapshot() {
        long version = displaySnapshotVersion.get();
        List<ProxyDisplay> snapshot = displaySnapshot;
        if (displaySnapshotCachedVersion == version) {
            return snapshot;
        }

        List<ProxyDisplay> refreshed = new ArrayList<>(displays.values());
        List<ProxyDisplay> updated = refreshed.isEmpty() ? List.of() : Collections.unmodifiableList(refreshed);
        displaySnapshot = updated;
        displaySnapshotCachedVersion = version;
        return updated;
    }

    private void scheduleSyncDisplay(ProxyDisplay display, Map<ChunkKey, Collection<Player>> memo) {
        if (display == null) {
            return;
        }

        if (!plugin.scheduler().isFolia()) {
            syncDisplay(display, memo);
            return;
        }
        Location loc = display.location();
        World world = loc.getWorld();
        if (world == null) {
            destroyForAllViewers(display);
            return;
        }
        plugin.scheduler().runAt(world, loc.getBlockX() >> 4, loc.getBlockZ() >> 4,
                () -> syncDisplay(display, new HashMap<>()));
    }

    private void queueSync(ProxyDisplay display) {
        if (display == null) {
            return;
        }
        pendingSync.add(display);
        if (pendingSyncScheduled.compareAndSet(false, true)) {
            plugin.scheduler().runLater(this::drainPendingSync, 1L);
        }
    }

    private void drainPendingSync() {
        // Reset the flag before draining: a concurrent queueSync either lands in this drain's iterator
        // or wins the CAS and schedules a fresh drain — updates are never lost, at worst one display
        // gets a redundant idempotent sync.
        pendingSyncScheduled.set(false);
        if (pendingSync.isEmpty()) {
            return;
        }
        boolean folia = plugin.scheduler().isFolia();
        // syncDisplay reads the chunk's tracked-player list, so run each group on its owning region.
        // Group by chunk to limit refreshes to changed displays and preserve the round-robin budget.
        Map<ChunkKey, List<ProxyDisplay>> byChunk = folia ? new HashMap<>() : null;
        Map<ChunkKey, Collection<Player>> memo = folia ? null : new HashMap<>();
        for (Iterator<ProxyDisplay> it = pendingSync.iterator(); it.hasNext(); ) {
            ProxyDisplay display = it.next();
            it.remove();
            if (!folia) {
                syncDisplay(display, memo);
                continue;
            }
            Location loc = display.location();
            World world = loc.getWorld();
            if (world == null) {
                destroyForAllViewers(display);
                continue;
            }
            byChunk.computeIfAbsent(
                    new ChunkKey(world, loc.getBlockX() >> 4, loc.getBlockZ() >> 4),
                    key -> new ArrayList<>()).add(display);
        }
        if (byChunk == null) {
            return;
        }
        for (Map.Entry<ChunkKey, List<ProxyDisplay>> entry : byChunk.entrySet()) {
            ChunkKey key = entry.getKey();
            List<ProxyDisplay> group = entry.getValue();
            // One memo per region task: the chunk lookup is shared by every display in the group, and the
            // map must not be touched from another region.
            plugin.scheduler().runAt(key.world(), key.x(), key.z(), () -> {
                Map<ChunkKey, Collection<Player>> regionMemo = new HashMap<>();
                for (ProxyDisplay display : group) {
                    syncDisplay(display, regionMemo);
                }
            });
        }
    }

    private record ChunkKey(World world, int x, int z) {
    }

    private void syncPlayer(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        UUID playerId = player.getUniqueId();
        onlinePlayers.put(playerId, player);
        Set<Integer> candidates = collectCandidateDisplayIds(player);
        for (Integer entityId : candidates) {
            ProxyDisplay display = displays.get(entityId);
            if (display != null) {
                syncDisplayForPlayer(display, player);
            }
        }

        Set<Integer> visible = visibleDisplaysByPlayer.get(playerId);
        if (visible == null || visible.isEmpty()) {
            return;
        }
        for (Integer entityId : new HashSet<>(visible)) {
            ProxyDisplay display = displays.get(entityId);
            if (display == null) {
                removeVisibleDisplay(playerId, entityId);
            } else if (!candidates.contains(entityId) && display.viewers.contains(playerId)) {
                destroyForViewer(player, display);
            }
        }
    }

    private Set<Integer> collectCandidateDisplayIds(Player player) {
        World world = player.getWorld();
        Map<Long, Set<Integer>> byChunk = displaysByChunk.get(world.getUID());
        if (byChunk == null || byChunk.isEmpty()) {
            return Set.of();
        }
        Location playerLocation = player.getLocation();
        int centerX = playerLocation.getBlockX() >> 4;
        int centerZ = playerLocation.getBlockZ() >> 4;
        int radius = candidateChunkRadius(viewDistance);
        Set<Integer> candidates = new HashSet<>();
        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                Set<Integer> ids = byChunk.get(ManagerSupport.chunkKey(x, z));
                if (ids != null) {
                    candidates.addAll(ids);
                }
            }
        }
        return candidates;
    }

    static int candidateChunkRadius(double distance) {
        return Math.max(1, (int) Math.ceil(Math.max(0.0D, distance) / 16.0D));
    }

    private void syncDisplayForPlayer(ProxyDisplay display, Player player) {
        if (display == null || displays.get(display.entityId) != display) {
            return;
        }
        UUID playerId = player == null ? null : player.getUniqueId();
        if (playerId == null || !onlinePlayers.containsKey(playerId)) {
            if (playerId != null) {
                display.viewers.remove(playerId);
                removeVisibleDisplay(playerId, display.entityId);
            }
            return;
        }

        // Cheap squared-distance pre-filter (syncPlayer iterates every display on join/teleport/respawn):
        // a same-world display beyond view distance that the player is not currently viewing needs no work,
        // so skip the isPlayerTrackingDisplayChunk chunk lookup (getChunkAt / getPlayersSeeingChunk) for it.
        // dx/dy/dz are only valid same-world; a current viewer or a cross-world display falls through so a
        // now-far or now-cross-world stale viewer is still cleaned up below.
        Location displayLocation = display.location();
        World displayWorld = displayLocation.getWorld();
        if (displayWorld != null && Objects.equals(player.getWorld(), displayWorld)
                && !display.viewers.contains(playerId)) {
            double dx = player.getX() - displayLocation.getX();
            double dy = player.getY() - displayLocation.getY();
            double dz = player.getZ() - displayLocation.getZ();
            if (dx * dx + dy * dy + dz * dz > viewDistanceSquared) {
                return;
            }
        }

        // This is the per-player path (join/teleport/respawn/world-change on Paper, and every Folia
        // sync). Unlike the periodic syncAll path — whose candidate set already comes from
        // getPlayersSeeingChunk — this path would otherwise show a display to anyone within the flat 64
        // blocks even if the display's chunk was never sent to them (a floating item in a
        // small-render-distance player's fog). Gate on chunk tracking so, like CE's furniture
        // meta-entity, a display only reaches a player who is actually tracking its chunk.
        if (isPlayerTrackingDisplayChunk(player, display) && shouldViewerSeeDisplay(player, display)) {
            if (!display.viewers.contains(playerId)) {
                spawnForViewer(player, display);
            }
        } else if (display.viewers.contains(playerId)) {
            destroyForViewer(player, display);
        }
    }

    private boolean isPlayerTrackingDisplayChunk(Player player, ProxyDisplay display) {
        Location location = display.location();
        World world = location.getWorld();
        if (world == null || !Objects.equals(player.getWorld(), world)) {
            return false;
        }
        // Folia invokes this on the player's entity scheduler, which may not own the display's chunk.
        // Skip chunk access there and let the following distance check determine visibility.
        if (plugin.scheduler().isFolia()) {
            return true;
        }
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return false;
        }
        return world.getChunkAt(chunkX, chunkZ).getPlayersSeeingChunk().contains(player);
    }

    private void syncDisplay(ProxyDisplay display, Map<ChunkKey, Collection<Player>> memo) {
        if (displays.get(display.entityId) != display) {
            return;
        }
        Location loc = display.location();
        World world = loc.getWorld();
        if (world == null) {
            destroyForAllViewers(display);
            return;
        }
        int chunkX = loc.getBlockX() >> 4;
        int chunkZ = loc.getBlockZ() >> 4;
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            destroyForAllViewers(display);
            return;
        }
        // Use Paper's chunk-level player tracking instead of world.getPlayers() + per-player distance
        // filter. The server already maintains "who can see this chunk" as O(1) data on the chunk
        // holder; pulling it directly skips a full-world player scan + distance loop. Mirrors CE's
        // BukkitWorld.getTrackedBy(ChunkPos) which goes through ChunkHolder.getPlayers(false).
        Collection<Player> players = memo == null
                ? world.getChunkAt(chunkX, chunkZ).getPlayersSeeingChunk()
                : memo.computeIfAbsent(new ChunkKey(world, chunkX, chunkZ),
                        key -> key.world().getChunkAt(key.x(), key.z()).getPlayersSeeingChunk());
        syncDisplay(display, players);
    }

    private void syncDisplay(ProxyDisplay display, Collection<Player> players) {
        if (players.isEmpty() && display.viewers.isEmpty()) {
            return;
        }

        // Chunk-tracking rarely exceeds a handful of players, so a small list beats building a hash
        // set per display per pass; the stale-viewer loop below does a linear contains against it.
        List<UUID> desiredViewers = new ArrayList<>(players.size());
        for (Player player : players) {
            if (!shouldViewerSeeDisplay(player, display)) {
                continue;
            }

            desiredViewers.add(player.getUniqueId());
            spawnForViewer(player, display);
        }

        // display.viewers is a ConcurrentHashMap key set: its weakly-consistent iterator tolerates the
        // removals done by destroyForViewer and it.remove(), so no defensive copy is needed.
        for (Iterator<UUID> it = display.viewers.iterator(); it.hasNext(); ) {
            UUID viewerId = it.next();
            if (desiredViewers.contains(viewerId)) {
                continue;
            }
            Player player = onlinePlayers.get(viewerId);
            if (player == null) {
                player = Bukkit.getPlayer(viewerId);
            }
            if (player != null) {
                destroyForViewer(player, display);
            } else {
                it.remove();
                removeVisibleDisplay(viewerId, display.entityId);
            }
        }
    }

    private boolean shouldViewerSeeDisplay(Player player, ProxyDisplay display) {
        if (player == null || !player.isOnline()) {
            return false;
        }

        Location location = display.location();
        if (location.getWorld() == null || !Objects.equals(player.getWorld(), location.getWorld())) {
            return false;
        }

        if (!plugin.scheduler().isFolia()
                && !location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return false;
        }

        // Compute squared distance by hand to avoid allocating a Location per visibility check.
        // Same world is already guaranteed (see check above), so no cross-world exception; equivalent to distanceSquared.
        double dx = player.getX() - location.getX();
        double dy = player.getY() - location.getY();
        double dz = player.getZ() - location.getZ();
        // Hysteresis: a display this viewer already sees keeps a few extra blocks, so the boundary itself
        // does not turn into spawn/destroy churn every pass.
        boolean alreadyShown = display.viewers.contains(player.getUniqueId());
        return DisplayCulling.isVisible(dx * dx + dy * dy + dz * dz, viewDistance,
                alreadyShown ? VIEW_HYSTERESIS : 0.0D);
    }

    private void spawnForViewer(Player player, ProxyDisplay display) {
        // Claim the viewer slot first: syncPlayer (the player's own scheduler) and syncAll/drainPendingSync
        // (the owning chunk's region task) can both reach this method for the same player, and sending the
        // spawn twice leaves a duplicate display entity behind after a single destroy.
        if (!display.viewers.add(player.getUniqueId())) {
            return;
        }
        try {
            NetWorkUser user = networkManager.getOnlineUser(player.getUniqueId());
            if (user == null || !user.isOnline()) {
                display.viewers.remove(player.getUniqueId());
                return;
            }
            user.sendPackets(display.spawnPackets, false);
            // Per-viewer ViewRange, like CE's furniture elements: the spawn metadata carries the base range,
            // this pins it for this viewer so a later cull only has to send a 0 without touching the entity.
            user.sendPacket(packets.createViewRangePacket(display.entityId, viewRangeMeta), false);
            visibleDisplaysByPlayer.computeIfAbsent(player.getUniqueId(), ignored -> ConcurrentHashMap.newKeySet())
                    .add(display.entityId);
            viewerSpawnPacketCount.incrementAndGet();
        } catch (Exception e) {
            // The packets did not go out, so the viewer is released again and the next sync retries.
            display.viewers.remove(player.getUniqueId());
            plugin.getLogger().warning(I18n.formatConsole("visual.proxy_spawn_failed",
                    "entity", display.entityId,
                    "player", player.getName(),
                    "error", e.getMessage()));
        }
    }

    private void destroyForViewer(Player player, ProxyDisplay display) {
        try {
            NetWorkUser user = networkManager.getOnlineUser(player.getUniqueId());
            if (user != null && user.isOnline()) {
                // A defensive addition, not CE's order: CE sends only the despawn for elements it does not
                // retain. Zeroing this viewer's range first costs one packet and covers a client that keeps
                // the entity for a frame after the removal.
                user.sendPacket(packets.createViewRangePacket(display.entityId, DisplayCulling.CULLED_RANGE), false);
                user.sendPacket(display.destroyPacket, false);
                viewerDestroyPacketCount.incrementAndGet();
            }
        } catch (Exception e) {
            plugin.getLogger().warning(I18n.formatConsole("visual.proxy_destroy_failed",
                    "entity", display.entityId,
                    "player", player.getName(),
                    "error", e.getMessage()));
        } finally {
            UUID playerId = player.getUniqueId();
            display.viewers.remove(playerId);
            removeVisibleDisplay(playerId, display.entityId);
        }
    }

    private void destroyForAllViewers(ProxyDisplay display) {
        for (UUID viewerId : new HashSet<>(display.viewers)) {
            Player player = onlinePlayers.get(viewerId);
            if (player != null) {
                if (plugin.scheduler().isFolia()) {
                    try {
                        plugin.scheduler().runForEntity(player, () -> {
                            if (display.viewers.contains(viewerId)) {
                                destroyForViewer(player, display);
                            }
                        });
                    } catch (RuntimeException e) {
                        display.viewers.remove(viewerId);
                        removeVisibleDisplay(viewerId, display.entityId);
                        onlinePlayers.remove(viewerId);
                    }
                } else {
                    destroyForViewer(player, display);
                }
            } else {
                display.viewers.remove(viewerId);
                removeVisibleDisplay(viewerId, display.entityId);
            }
        }
    }

    private void sendUpdateForAllViewers(ProxyDisplay display, Object positionPacket, Object metadataPacket) {
        for (UUID viewerId : new HashSet<>(display.viewers)) {
            Player player = onlinePlayers.get(viewerId);
            if (player != null) {
                if (plugin.scheduler().isFolia()) {
                    try {
                        plugin.scheduler().runForEntity(player, () -> {
                            if (display.viewers.contains(viewerId)) {
                                sendUpdateForViewer(player, display, positionPacket, metadataPacket);
                            }
                        });
                    } catch (RuntimeException e) {
                        display.viewers.remove(viewerId);
                        removeVisibleDisplay(viewerId, display.entityId);
                        onlinePlayers.remove(viewerId);
                    }
                } else {
                    sendUpdateForViewer(player, display, positionPacket, metadataPacket);
                }
            } else {
                display.viewers.remove(viewerId);
                removeVisibleDisplay(viewerId, display.entityId);
            }
        }
    }

    private void sendUpdateForViewer(Player player, ProxyDisplay display, Object positionPacket, Object metadataPacket) {
        try {
            NetWorkUser user = networkManager.getOnlineUser(player.getUniqueId());
            if (user == null || !user.isOnline()) {
                UUID playerId = player.getUniqueId();
                display.viewers.remove(playerId);
                removeVisibleDisplay(playerId, display.entityId);
                return;
            }
            if (positionPacket == null) {
                user.sendPacket(metadataPacket, false);
            } else {
                user.sendPackets(List.of(positionPacket, metadataPacket), false);
            }
            viewerUpdatePacketCount.incrementAndGet();
        } catch (Exception e) {
            plugin.getLogger().warning(I18n.formatConsole("visual.proxy_update_failed",
                    "entity", display.entityId,
                    "player", player.getName(),
                    "error", e.getMessage()));
        }
    }

    private void logDisplayBuildFailure(String op, DisplaySpec spec, Throwable t) {
        String item = spec != null && spec.itemStack() != null ? spec.itemStack().getType().name() : "null";
        plugin.getLogger().log(Level.WARNING,
                "Skipped item display " + op + " for " + item + " so the interaction is not aborted", t);
    }

    private void clearViewer(UUID playerId) {
        if (playerId == null) {
            return;
        }
        Set<Integer> visible = visibleDisplaysByPlayer.remove(playerId);
        if (visible == null) {
            return;
        }
        for (Integer entityId : visible) {
            ProxyDisplay display = displays.get(entityId);
            if (display != null) {
                display.viewers.remove(playerId);
            }
        }
    }

    private void removeVisibleDisplay(UUID playerId, int entityId) {
        Set<Integer> visible = visibleDisplaysByPlayer.get(playerId);
        if (visible == null) {
            return;
        }
        visible.remove(entityId);
        if (visible.isEmpty()) {
            visibleDisplaysByPlayer.remove(playerId, visible);
        }
    }

    private DisplaySpec normalize(DisplaySpec spec) {
        Location location = spec.location().clone();
        ItemStack itemStack = spec.itemStack().clone();
        itemStack.setAmount(1);
        return new DisplaySpec(location, itemStack, spec.itemTransform(), spec.transformation(),
                spec.interpolationDurationTicks(), spec.interpolationDelayTicks());
    }

    public List<String> debugStats() {
        long elapsedMs = Math.max(1L, System.currentTimeMillis() - debugStatsResetEpochMs);
        double seconds = elapsedMs / 1000.0;
        long iSpawn = itemSpawnCount.get();
        long iUpdate = itemUpdateCount.get();
        long tSpawn = textSpawnCount.get();
        long tUpdate = textUpdateCount.get();
        long tDiff = textUpdateDiffHitCount.get();
        long destroy = destroyCount.get();
        long vSpawn = viewerSpawnPacketCount.get();
        long vDestroy = viewerDestroyPacketCount.get();
        long vUpdate = viewerUpdatePacketCount.get();
        long sync = syncRunCount.get();
        long totalPackets = vSpawn + vDestroy + vUpdate;
        long totalAttempts = tUpdate + tDiff;
        double diffRate = totalAttempts == 0 ? 0.0 : (double) tDiff / totalAttempts * 100.0;
        return List.of(
                String.format("displays=%d (item=%d text=%d) window=%.1fs", displays.size(),
                        countByKind(false), countByKind(true), seconds),
                String.format("item: spawn=%d update=%d (%.1f/s)", iSpawn, iUpdate, (iSpawn + iUpdate) / seconds),
                String.format("text: spawn=%d update=%d diff-hit=%d (%.1f%% short-circuit)",
                        tSpawn, tUpdate, tDiff, diffRate),
                String.format("destroy=%d sync-runs=%d", destroy, sync),
                String.format("viewer pkts: spawn=%d destroy=%d update=%d total=%d (%.1f/s)",
                        vSpawn, vDestroy, vUpdate, totalPackets, totalPackets / seconds)
        );
    }

    public void resetDebugStats() {
        resetCounters(itemSpawnCount, itemUpdateCount, textSpawnCount, textUpdateCount,
                textUpdateDiffHitCount, destroyCount, viewerSpawnPacketCount, viewerDestroyPacketCount,
                viewerUpdatePacketCount, syncRunCount);
        debugStatsResetEpochMs = System.currentTimeMillis();
    }

    private static void resetCounters(AtomicLong... counters) {
        for (AtomicLong counter : counters) {
            counter.set(0);
        }
    }

    private int countByKind(boolean text) {
        int n = 0;
        for (ProxyDisplay d : displays.values()) {
            if (d.isText() == text) n++;
        }
        return n;
    }

    private TextDisplaySpec normalizeText(TextDisplaySpec spec) {
        return new TextDisplaySpec(
                spec.location().clone(),
                spec.text(),
                spec.transformation(),
                spec.backgroundColor(),
                spec.shadowed(),
                spec.seeThrough());
    }

    private static final class ProxyDisplay {
        private final int entityId;
        private final UUID entityUuid;
        // Written by the block's region thread in updateDisplay() and read by other region threads (player/global)
        // on the spawn/visibility path; volatile ensures cross-thread visibility, avoiding stale item/position or torn state.
        // spawnPackets is always assigned last and is a consistent immutable List, so readers see the full old or full new value.
        // Exactly one of (itemSpec, textSpec) is non-null for the lifetime of the proxy.
        private volatile DisplaySpec itemSpec;
        private volatile TextDisplaySpec textSpec;
        private final Set<UUID> viewers = ConcurrentHashMap.newKeySet();
        private volatile Object spawnPacket;
        private volatile Object metadataPacket;
        private final Object destroyPacket;
        private volatile List<Object> spawnPackets;

        private ProxyDisplay(int entityId, UUID entityUuid,
                             DisplaySpec itemSpec, TextDisplaySpec textSpec,
                             Object spawnPacket, Object metadataPacket, Object destroyPacket) {
            this.entityId = entityId;
            this.entityUuid = entityUuid;
            this.itemSpec = itemSpec;
            this.textSpec = textSpec;
            this.spawnPacket = spawnPacket;
            this.metadataPacket = metadataPacket;
            this.destroyPacket = destroyPacket;
            this.spawnPackets = List.of(spawnPacket, metadataPacket);
        }

        Location location() {
            return itemSpec != null ? itemSpec.location() : textSpec.location();
        }

        boolean isText() {
            return textSpec != null;
        }
    }
}
