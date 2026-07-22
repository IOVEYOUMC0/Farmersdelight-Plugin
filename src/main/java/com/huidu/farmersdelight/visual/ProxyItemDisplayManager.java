package com.huidu.farmersdelight.visual;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import it.unimi.dsi.fastutil.ints.IntList;
import net.momirealms.craftengine.bukkit.entity.data.BaseEntityData;
import net.momirealms.craftengine.bukkit.entity.data.DisplayData;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.bukkit.plugin.network.BukkitNetworkManager;
import net.momirealms.craftengine.core.plugin.network.NetWorkUser;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundAddEntityPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundSetEntityDataPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundTeleportEntityPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.world.entity.EntityTypesProxy;
import net.momirealms.craftengine.proxy.minecraft.world.entity.PositionMoveRotationProxy;
import net.momirealms.craftengine.proxy.minecraft.world.phys.Vec3Proxy;
import net.momirealms.craftengine.core.util.MiscUtils;
import net.momirealms.craftengine.core.util.VersionHelper;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class ProxyItemDisplayManager implements Listener, ItemDisplayManager {

    private static final double DEFAULT_VIEW_DISTANCE = 64.0D;
    private static final int DEFAULT_SYNC_INTERVAL_TICKS = 20;
    private static final int DEFAULT_SYNC_BATCH_SIZE = 256;

    private final FarmersDelightPlugin plugin;
    private final BukkitNetworkManager networkManager;
    private final AtomicInteger nextEntityId = new AtomicInteger(2_000_000);
    private final Map<Integer, ProxyDisplay> displays = new ConcurrentHashMap<>();
    // Index of display ids by (world, chunk) so onChunkUnload removes a chunk's displays in O(displays
    // in that chunk) instead of scanning the whole map. FD displays are anchored to a block, so a
    // display never changes chunk after creation — the index only needs add-on-create / remove-on-destroy.
    private final Map<UUID, Map<Long, Set<Integer>>> displaysByChunk = new ConcurrentHashMap<>();
    private final Map<UUID, Player> onlinePlayers = new ConcurrentHashMap<>();
    private final AtomicLong displaySnapshotVersion = new AtomicLong();
    private volatile List<ProxyDisplay> displaySnapshot = List.of();
    private volatile long displaySnapshotCachedVersion = -1L;
    // volatile + lock: ensureSyncTask() is called from Folia region threads (createDisplay/
    // updateDisplay run in block-entity ticks) while reload()/cleanup() cancel it from the global
    // thread. Without the lock two threads could both observe a null handle and start two repeating
    // sync tasks. R-CONC-002.
    private volatile PluginTask syncTask;
    private final Object syncTaskLock = new Object();
    private double viewDistance = DEFAULT_VIEW_DISTANCE;
    private double viewDistanceSquared = DEFAULT_VIEW_DISTANCE * DEFAULT_VIEW_DISTANCE;
    // Display ViewRange metadata: the client renders the display within ~ViewRange × 64 blocks. Derived
    // from the send distance so the client's render cutoff tracks the server's send cutoff — otherwise a
    // raised view-distance would send displays the client (ViewRange fixed at 1.0 ≈ 64) refuses to draw.
    // Default 64 → 1.0, unchanged. Mirrors CE furniture's viewRange-from-config approach.
    private volatile float viewRangeMeta = 1.0f;
    private int syncIntervalTicks = DEFAULT_SYNC_INTERVAL_TICKS;
    private int syncBatchSize = DEFAULT_SYNC_BATCH_SIZE;
    private int syncCursor;
    // Create/update bursts (a stove restoring 4 slots, a cutting board re-stacking) each used to pay an
    // immediate per-display visibility sync. Queue them instead and drain once next tick so same-chunk
    // displays share one chunk-player lookup. Existing viewers still get their update packets
    // synchronously (sendUpdateForAllViewers); only the "player newly in range" sync is deferred <=1t.
    private final Set<ProxyDisplay> pendingSync = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean pendingSyncScheduled = new AtomicBoolean();

    // ── Debug metrics ──────────────────────────────────────────────────
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
        BukkitCraftEngine craftEngine = BukkitCraftEngine.instance();
        if (craftEngine != null) {
            this.networkManager = craftEngine.networkManager();
        } else {
            this.networkManager = null;
        }
        if (isAvailable()) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
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

    public void reload() {
        viewDistance = Math.max(8.0D, plugin.getConfig().getDouble(
                "performance.proxy-item-display-view-distance", DEFAULT_VIEW_DISTANCE));
        viewDistanceSquared = viewDistance * viewDistance;
        viewRangeMeta = (float) (viewDistance / 64.0D);
        syncIntervalTicks = Math.max(1, plugin.getConfig().getInt(
                "performance.proxy-item-display-sync-interval-ticks", DEFAULT_SYNC_INTERVAL_TICKS));
        syncBatchSize = Math.max(1, plugin.getConfig().getInt(
                "performance.proxy-item-display-sync-batch-size", DEFAULT_SYNC_BATCH_SIZE));
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
            int entityId = nextEntityId.getAndIncrement();
            UUID entityUuid = fastRandomUuid();
            Object spawnPacket = createItemSpawnPacket(entityId, entityUuid, normalizedSpec);
            Object metadataPacket = createItemMetadataPacket(entityId, normalizedSpec);
            Object destroyPacket = createDestroyPacket(entityId);
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
        } catch (Throwable t) {
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
        int entityId = nextEntityId.getAndIncrement();
        UUID entityUuid = fastRandomUuid();
        Object spawnPacket = createTextSpawnPacket(entityId, entityUuid, normalizedSpec);
        Object metadataPacket = createTextMetadataPacket(entityId, normalizedSpec);
        Object destroyPacket = createDestroyPacket(entityId);
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
    public boolean updateText(int entityId, net.kyori.adventure.text.Component text) {
        if (!isAvailable() || text == null) {
            return false;
        }
        ProxyDisplay display = displays.get(entityId);
        if (display == null || !display.isText()) {
            return false;
        }
        TextDisplaySpec current = display.textSpec;
        if (current.text().equals(text)) {
            // Skip the metadata broadcast when text matches what viewers already see.
            // Mirrors BuffBossbarManager's setter-diff pattern (R-PERF-004).
            textUpdateDiffHitCount.incrementAndGet();
            return true;
        }
        TextDisplaySpec updated = new TextDisplaySpec(
                current.location(), text, current.transformation(),
                current.backgroundColor(), current.shadowed(), current.seeThrough());
        display.textSpec = updated;
        display.metadataPacket = createTextMetadataPacket(entityId, updated);
        display.spawnPacket = createTextSpawnPacket(entityId, display.entityUuid, updated);
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
        Object newSpawnPacket;
        Object newMetadataPacket;
        try {
            // Build the packets before mutating the display so a build failure leaves the old visual intact
            // and cannot propagate into the caller (see createDisplay).
            newSpawnPacket = createItemSpawnPacket(entityId, display.entityUuid, normalizedSpec);
            newMetadataPacket = createItemMetadataPacket(entityId, normalizedSpec);
        } catch (Throwable t) {
            logDisplayBuildFailure("update", spec, t);
            return false;
        }
        display.itemSpec = normalizedSpec;
        display.spawnPacket = newSpawnPacket;
        display.metadataPacket = newMetadataPacket;
        display.spawnPackets = List.of(display.spawnPacket, display.metadataPacket);
        // Only send a position (teleport) packet when the display actually moved. FD's item displays are
        // stationary in-slot — a cutting-board carve/count change updates the item + metadata, never
        // x/y/z — so this drops a redundant position packet per update. Mirrors updateText's null-position
        // path; the packet carries only x/y/z (yaw/pitch are always 0, rotation lives in the transform).
        Object positionPacket = previousSpec != null
                && sameDisplayPosition(previousSpec.location(), normalizedSpec.location())
                ? null
                : createPositionPacket(entityId, normalizedSpec.location());
        sendUpdateForAllViewers(display, positionPacket, display.metadataPacket);
        queueSync(display);
        itemUpdateCount.incrementAndGet();
        return true;
    }

    /** True when two display locations occupy the same world + x/y/z. Position packets carry only
     *  coordinates (yaw/pitch are always 0), so an unchanged position needs no teleport packet. */
    private static boolean sameDisplayPosition(Location a, Location b) {
        return a != null && b != null
                && a.getWorld() == b.getWorld()
                && a.getX() == b.getX()
                && a.getY() == b.getY()
                && a.getZ() == b.getZ();
    }

    @Override
    public void destroyDisplay(int entityId) {
        ProxyDisplay removed = displays.remove(entityId);
        if (removed != null) {
            unindexDisplay(removed);
            markDisplaySnapshotDirty();
            destroyForAllViewers(removed);
            destroyCount.incrementAndGet();
            stopSyncTaskIfIdle();
        }
    }

    private static long chunkKeyOf(Location location) {
        return ((long) (location.getBlockX() >> 4) << 32) | ((location.getBlockZ() >> 4) & 0xffffffffL);
    }

    private void indexDisplay(ProxyDisplay display) {
        Location loc = display.location();
        if (loc.getWorld() == null) {
            return;
        }
        displaysByChunk.computeIfAbsent(loc.getWorld().getUID(), k -> new ConcurrentHashMap<>())
                .computeIfAbsent(chunkKeyOf(loc), k -> ConcurrentHashMap.newKeySet())
                .add(display.entityId);
    }

    private void unindexDisplay(ProxyDisplay display) {
        Location loc = display.location();
        if (loc.getWorld() == null) {
            return;
        }
        Map<Long, Set<Integer>> byChunk = displaysByChunk.get(loc.getWorld().getUID());
        if (byChunk == null) {
            return;
        }
        long chunkKey = chunkKeyOf(loc);
        Set<Integer> ids = byChunk.get(chunkKey);
        if (ids != null) {
            ids.remove(display.entityId);
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
    public int cleanup() {
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
        return removed;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        onlinePlayers.put(event.getPlayer().getUniqueId(), event.getPlayer());
        plugin.scheduler().runLaterForEntity(event.getPlayer(), () -> syncPlayer(event.getPlayer()), 5L);
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        onlinePlayers.put(event.getPlayer().getUniqueId(), event.getPlayer());
        clearViewer(event.getPlayer().getUniqueId());
        plugin.scheduler().runLaterForEntity(event.getPlayer(), () -> syncPlayer(event.getPlayer()), 2L);
    }

    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        onlinePlayers.put(event.getPlayer().getUniqueId(), event.getPlayer());
        plugin.scheduler().runLaterForEntity(event.getPlayer(), () -> syncPlayer(event.getPlayer()), 2L);
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        onlinePlayers.put(event.getPlayer().getUniqueId(), event.getPlayer());
        clearViewer(event.getPlayer().getUniqueId());
        plugin.scheduler().runLaterForEntity(event.getPlayer(), () -> syncPlayer(event.getPlayer()), 2L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        onlinePlayers.remove(event.getPlayer().getUniqueId());
        clearViewer(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        Map<Long, Set<Integer>> byChunk = displaysByChunk.get(event.getWorld().getUID());
        if (byChunk == null) {
            return;
        }
        long chunkKey = ((long) event.getChunk().getX() << 32) | (event.getChunk().getZ() & 0xffffffffL);
        Set<Integer> ids = byChunk.get(chunkKey);
        if (ids == null || ids.isEmpty()) {
            return;
        }
        // Snapshot before destroying — destroyDisplay mutates this same set via unindexDisplay.
        for (Integer entityId : new ArrayList<>(ids)) {
            destroyDisplay(entityId);
        }
    }

    private void startSyncTask() {
        syncTask = plugin.scheduler().runRepeating(this::syncAll, syncIntervalTicks, syncIntervalTicks);
    }

    /** Starts the sync task unless one is already running. The whole check-then-start is inside the lock,
     *  with no unlocked probe of the handle: an unlocked read could observe a non-null handle for a task
     *  stopSyncTaskIfIdle is concurrently cancelling, and return without starting a replacement, leaving
     *  the displays with no sync pass. Callers are display creation and reload only, so the monitor is
     *  effectively uncontended. Same shape as TrayManager.ensureQueuedSyncTask. */
    private void ensureSyncTask() {
        synchronized (syncTaskLock) {
            if (syncTask == null) {
                startSyncTask();
            }
        }
    }

    /** Stops the sync task once the display map has drained, so a server with no FD displays present does
     *  not keep a repeating pass alive. The unlocked displays.isEmpty() probe is only a hint that skips the
     *  monitor while displays exist — never cancelling is always safe. The authoritative recheck happens
     *  inside the lock, and because ensureSyncTask takes the same lock, a display put into the map
     *  concurrently either is seen by that recheck (no cancel) or its ensureSyncTask observes the cleared
     *  handle afterwards and starts a fresh pass. */
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
        // cannot change mid-pass and the memo is exactly equivalent to per-display fresh calls. Folia
        // takes the per-player scheduling branch which never queries chunk tracking — no memo needed.
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

        scheduleDisplayForPlayers(display);
    }

    /** Queues a display for a coalesced visibility sync one tick later. Bursts of creates/updates in
     *  the same tick collapse into one drain sharing one chunk-player lookup per chunk. */
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
        Map<ChunkKey, Collection<Player>> memo = plugin.scheduler().isFolia() ? null : new HashMap<>();
        for (Iterator<ProxyDisplay> it = pendingSync.iterator(); it.hasNext(); ) {
            ProxyDisplay display = it.next();
            it.remove();
            scheduleSyncDisplay(display, memo);
        }
    }

    /** Random v4 UUID from ThreadLocalRandom. The fake display entities' UUIDs only ever ride in the
     *  spawn packet — no persistence, no equality against real entity UUIDs — so SecureRandom's
     *  synchronized entropy (UUID.randomUUID) buys nothing on this path. */
    private static UUID fastRandomUuid() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long msb = (random.nextLong() & 0xFFFF_FFFF_FFFF_0FFFL) | 0x0000_0000_0000_4000L;
        long lsb = (random.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(msb, lsb);
    }

    /** Chunk identity for the per-pass player-tracking memo. Includes the world so identical
     *  coordinates in different worlds never collide. */
    private record ChunkKey(World world, int x, int z) {
    }

    private void scheduleDisplayForPlayers(ProxyDisplay display) {
        if (displays.get(display.entityId) != display) {
            return;
        }
        World displayWorld = display.location().getWorld();
        if (displayWorld == null) {
            return;
        }
        for (Player player : onlinePlayers.values()) {
            // Cheap pre-filter: skip players in other worlds before paying the per-player scheduling cost.
            // syncDisplayForPlayer still rechecks distance on the player's own thread.
            if (player == null || !displayWorld.equals(player.getWorld())) {
                continue;
            }
            try {
                plugin.scheduler().runForEntity(player, () -> syncDisplayForPlayer(display, player));
            } catch (RuntimeException e) {
                onlinePlayers.remove(player.getUniqueId());
                display.viewers.remove(player.getUniqueId());
            }
        }
    }

    private void syncPlayer(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        onlinePlayers.put(player.getUniqueId(), player);
        for (ProxyDisplay display : displays.values()) {
            syncDisplayForPlayer(display, player);
        }
    }

    private void syncDisplayForPlayer(ProxyDisplay display, Player player) {
        if (display == null || displays.get(display.entityId) != display) {
            return;
        }
        UUID playerId = player == null ? null : player.getUniqueId();
        if (playerId == null || !onlinePlayers.containsKey(playerId)) {
            if (playerId != null) {
                display.viewers.remove(playerId);
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

    /** Whether player is tracking (has been sent) the display's chunk. Uses Paper's
     *  chunk-holder player set, which already encodes each player's own view-distance — the authoritative
     *  "can this player see here" signal, mirroring CE routing furniture through the vanilla entity
     *  tracker. Returns false for an unloaded chunk or a cross-world player. */
    private boolean isPlayerTrackingDisplayChunk(Player player, ProxyDisplay display) {
        Location location = display.location();
        World world = location.getWorld();
        if (world == null || !Objects.equals(player.getWorld(), world)) {
            return false;
        }
        // On Folia this method runs on the PLAYER's region thread (scheduleDisplayForPlayers ->
        // runForEntity(player)). The display's chunk may belong to a different region, and touching it there
        // (isChunkLoaded / getChunkAt) trips Folia's region-owner check and throws. shouldViewerSeeDisplay,
        // called right after this, already bounds visibility by distance without any chunk access and itself
        // skips its isChunkLoaded probe on Folia — so on Folia skip the chunk-tracking gate and let that
        // distance check be authoritative (the pre-#042 flat-distance behaviour, region-safe).
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
            if (!display.viewers.contains(player.getUniqueId())) {
                spawnForViewer(player, display);
            }
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
        return dx * dx + dy * dy + dz * dz <= viewDistanceSquared;
    }

    private void spawnForViewer(Player player, ProxyDisplay display) {
        try {
            NetWorkUser user = networkManager.getOnlineUser(player.getUniqueId());
            if (user == null || !user.isOnline()) {
                return;
            }
            user.sendPackets(display.spawnPackets, false);
            display.viewers.add(player.getUniqueId());
            viewerSpawnPacketCount.incrementAndGet();
        } catch (Exception e) {
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
                user.sendPacket(display.destroyPacket, false);
                viewerDestroyPacketCount.incrementAndGet();
            }
        } catch (Exception e) {
            plugin.getLogger().warning(I18n.formatConsole("visual.proxy_destroy_failed",
                    "entity", display.entityId,
                    "player", player.getName(),
                    "error", e.getMessage()));
        } finally {
            display.viewers.remove(player.getUniqueId());
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
                        onlinePlayers.remove(viewerId);
                    }
                } else {
                    destroyForViewer(player, display);
                }
            } else {
                display.viewers.remove(viewerId);
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
                        onlinePlayers.remove(viewerId);
                    }
                } else {
                    sendUpdateForViewer(player, display, positionPacket, metadataPacket);
                }
            } else {
                display.viewers.remove(viewerId);
            }
        }
    }

    private void sendUpdateForViewer(Player player, ProxyDisplay display, Object positionPacket, Object metadataPacket) {
        try {
            NetWorkUser user = networkManager.getOnlineUser(player.getUniqueId());
            if (user == null || !user.isOnline()) {
                display.viewers.remove(player.getUniqueId());
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

    private Object createItemSpawnPacket(int entityId, UUID entityUuid, DisplaySpec spec) {
        return buildSpawnPacket(entityId, entityUuid, spec.location(), EntityTypesProxy.ITEM_DISPLAY);
    }

    private Object createTextSpawnPacket(int entityId, UUID entityUuid, TextDisplaySpec spec) {
        return buildSpawnPacket(entityId, entityUuid, spec.location(), EntityTypesProxy.TEXT_DISPLAY);
    }

    private Object buildSpawnPacket(int entityId, UUID entityUuid, Location location, Object entityType) {
        return ClientboundAddEntityPacketProxy.INSTANCE.newInstance(
                entityId,
                entityUuid,
                location.getX(),
                location.getY(),
                location.getZ(),
                0.0F,
                0.0F,
                entityType,
                0,
                Vec3Proxy.ZERO,
                0.0D
        );
    }

    private Object createPositionPacket(int entityId, Location location) {
        if (VersionHelper.isOrAbove1_21_2) {
            Object position = Vec3Proxy.INSTANCE.newInstance(location.getX(), location.getY(), location.getZ());
            Object values = PositionMoveRotationProxy.INSTANCE.newInstance(position, Vec3Proxy.ZERO, 0.0F, 0.0F);
            return ClientboundEntityPositionSyncPacketProxy.INSTANCE.newInstance(entityId, values, false);
        }

        Object packet = ClientboundTeleportEntityPacketProxy.UNSAFE_CONSTRUCTOR.newInstance();
        ClientboundTeleportEntityPacketProxy.INSTANCE.setId(packet, entityId);
        ClientboundTeleportEntityPacketProxy.INSTANCE.setX(packet, location.getX());
        ClientboundTeleportEntityPacketProxy.INSTANCE.setY(packet, location.getY());
        ClientboundTeleportEntityPacketProxy.INSTANCE.setZ(packet, location.getZ());
        ClientboundTeleportEntityPacketProxy.INSTANCE.setYRot(packet, MiscUtils.packDegrees(0.0F));
        ClientboundTeleportEntityPacketProxy.INSTANCE.setXRot(packet, MiscUtils.packDegrees(0.0F));
        ClientboundTeleportEntityPacketProxy.INSTANCE.setOnGround(packet, false);
        return packet;
    }

    private void logDisplayBuildFailure(String op, DisplaySpec spec, Throwable t) {
        String item = spec != null && spec.itemStack() != null ? spec.itemStack().getType().name() : "null";
        plugin.getLogger().log(java.util.logging.Level.WARNING,
                "Skipped item display " + op + " for " + item + " so the interaction is not aborted", t);
    }

    private Object createItemMetadataPacket(int entityId, DisplaySpec spec) {
        List<Object> values = new ArrayList<>();
        BaseEntityData.NoGravity.addEntityData(true, values);
        BaseEntityData.Silent.addEntityData(true, values);

        var wrappedItem = BukkitItemManager.instance().wrap(spec.itemStack());
        if (wrappedItem != null && !wrappedItem.isEmpty()) {
            DisplayData.ItemDisplayData.ItemStack.addEntityData(wrappedItem.minecraftItem(), values);
        } else if (spec.itemStack() != null && !spec.itemStack().getType().isAir()) {
            // The display item could not be wrapped into a client item, so the entity would render empty.
            // Surface it rather than showing a silently invisible display.
            plugin.getLogger().warning("Item display for " + spec.itemStack().getType().name()
                    + " has no renderable client item (wrap returned empty)");
        }

        var transformation = spec.transformation();
        DisplayData.ItemDisplayData.Translation.addEntityData(transformation.getTranslation(), values);
        DisplayData.ItemDisplayData.Scale.addEntityData(transformation.getScale(), values);
        DisplayData.ItemDisplayData.LeftRotation.addEntityData(transformation.getLeftRotation(), values);
        DisplayData.ItemDisplayData.RightRotation.addEntityData(transformation.getRightRotation(), values);
        DisplayData.ItemDisplayData.ItemTransform.addEntityData(toCeDisplayContext(spec.itemTransform()), values);
        DisplayData.ItemDisplayData.ShadowRadius.addEntityData(0.0F, values);
        DisplayData.ItemDisplayData.ShadowStrength.addEntityData(0.0F, values);
        DisplayData.ItemDisplayData.Width.addEntityData(0.0F, values);
        DisplayData.ItemDisplayData.Height.addEntityData(0.0F, values);
        DisplayData.ItemDisplayData.ViewRange.addEntityData(viewRangeMeta, values);
        return createEntityDataPacket(entityId, values);
    }

    private Object createTextMetadataPacket(int entityId, TextDisplaySpec spec) {
        List<Object> values = new ArrayList<>();
        BaseEntityData.NoGravity.addEntityData(true, values);
        BaseEntityData.Silent.addEntityData(true, values);

        var transformation = spec.transformation();
        DisplayData.Translation.addEntityData(transformation.getTranslation(), values);
        DisplayData.Scale.addEntityData(transformation.getScale(), values);
        DisplayData.LeftRotation.addEntityData(transformation.getLeftRotation(), values);
        DisplayData.RightRotation.addEntityData(transformation.getRightRotation(), values);
        // Billboard CENTER (3) — text always faces the viewer.
        DisplayData.BillboardConstraints.addEntityData((byte) 3, values);
        DisplayData.ViewRange.addEntityData(viewRangeMeta, values);

        // Text bodies for cooking-pot progress are pure ASCII ("100%" / "Apple 80%"), so a plain-text
        // round-trip through ComponentProxy.literal avoids the shaded-Adventure bridge in
        // CE's ComponentUtils.adventureToMinecraft (whose Component arg resolves to CE's relocated
        // adventure package, unreachable from addon compile classpath).
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(spec.text());
        Object componentValue = net.momirealms.craftengine.proxy.minecraft.network.chat.ComponentProxy.INSTANCE.literal(plain);
        DisplayData.TextDisplayData.Text.addEntityData(componentValue, values);
        DisplayData.TextDisplayData.BackgroundColor.addEntityData(colorToArgb(spec.backgroundColor()), values);
        byte flags = net.momirealms.craftengine.bukkit.entity.data.DisplayData.TextDisplayData.encodeFlags(
                spec.shadowed(),
                spec.seeThrough(),
                false,
                net.momirealms.craftengine.core.entity.display.TextDisplayAlignment.CENTER);
        DisplayData.TextDisplayData.Flags.addEntityData(flags, values);
        return createEntityDataPacket(entityId, values);
    }

    private static int colorToArgb(Color color) {
        if (color == null) return 0;
        return (color.getAlpha() << 24) | (color.getRed() << 16) | (color.getGreen() << 8) | color.getBlue();
    }

    private Object createEntityDataPacket(int entityId, List<?> values) {
        return ClientboundSetEntityDataPacketProxy.INSTANCE.newInstance(entityId, values);
    }

    private Object createDestroyPacket(int entityId) {
        return ClientboundRemoveEntitiesPacketProxy.INSTANCE.newInstance(IntList.of(entityId));
    }

    private void clearViewer(UUID playerId) {
        if (playerId == null) {
            return;
        }
        for (ProxyDisplay display : displays.values()) {
            display.viewers.remove(playerId);
        }
    }

    private byte toCeDisplayContext(ItemDisplay.ItemDisplayTransform transform) {
        if (transform == null) {
            return 0;
        }
        return switch (transform) {
            case THIRDPERSON_LEFTHAND -> 1;
            case THIRDPERSON_RIGHTHAND -> 2;
            case FIRSTPERSON_LEFTHAND -> 3;
            case FIRSTPERSON_RIGHTHAND -> 4;
            case HEAD -> 5;
            case GUI -> 6;
            case GROUND -> 7;
            case FIXED -> 8;
            default -> 0;
        };
    }

    private DisplaySpec normalize(DisplaySpec spec) {
        Location location = spec.location().clone();
        ItemStack itemStack = spec.itemStack().clone();
        itemStack.setAmount(1);
        return new DisplaySpec(location, itemStack, spec.itemTransform(), spec.transformation());
    }

    /** Snapshot of business counters since the last resetDebugStats() call. One-line-per-metric
     *  format so /fd debugtools status (and profile) can pipe straight to chat. */
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
        itemSpawnCount.set(0);
        itemUpdateCount.set(0);
        textSpawnCount.set(0);
        textUpdateCount.set(0);
        textUpdateDiffHitCount.set(0);
        destroyCount.set(0);
        viewerSpawnPacketCount.set(0);
        viewerDestroyPacketCount.set(0);
        viewerUpdatePacketCount.set(0);
        syncRunCount.set(0);
        debugStatsResetEpochMs = System.currentTimeMillis();
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

