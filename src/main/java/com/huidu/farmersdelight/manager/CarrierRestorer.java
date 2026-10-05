package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.config.ConfigSectionReader;
import com.huidu.farmersdelight.visual.RealDisplayCuller;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ItemUtils;
import com.huidu.farmersdelight.util.scheduler.RegionDispatcher;
import com.huidu.farmersdelight.util.scheduler.RegionTasks;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Restores the vanilla appearance of the vanilla fence and gate states the rope fence and rope fence
 * gate borrow as their carriers.
 *
 *
 * The two blocks are drawn by CraftEngine block entity renderers, but their carriers are still real
 * vanilla block states and CraftEngine writes the empty variant model for every visual state a custom
 * block claims. A genuinely vanilla crimson fence (or warped fence gate) standing in one of those
 * states therefore renders as nothing.
 *
 *
 * This class detects exactly those blocks and puts a {@link BlockDisplay} on their position carrying the
 * same block data. The client resolves that block data through the same resource pack, so the display
 * shows the untouched vanilla model while CraftEngine's empty variant stays hidden underneath it. Only
 * the borrowed states are restored; every other block is left to the client's own block rendering.
 *
 *
 * The cost is one live entity per affected vanilla block, so the work is bounded on every axis: chunks
 * are tracked only while resident, displays are created lazily and only while the world is under a
 * configurable entity budget, scanning and verification are throttled slices, and nothing is written to
 * the world save (the displays are non-persistent and are rebuilt from the chunk on load).
 */
public final class CarrierRestorer {

    /** Marker key: only entities carrying it are ever touched by this class. */
    public static final NamespacedKey KIND = NamespacedKey.fromString("farmersdelight:rope_carrier_restore");

    private static final boolean[] BOOLEANS = {false, true};

    /** Every carrier used by farmersdelight:rope_fence and farmersdelight:rope_fence_gate. */
    private static final Set<String> HIJACKED = buildHijackedStates();

    private static final int DEFAULT_MAX_ENTITIES = 4096;
    private static final int DEFAULT_ENTITIES_PER_TICK = 8;
    private static final int DEFAULT_VERIFY_PER_TICK = 64;
    private static final int DEFAULT_SCAN_CHUNKS_PER_TICK = 4;
    private static final int DEFAULT_SCAN_COLUMN_HEIGHT = 96;

    private final FarmersDelightPlugin plugin;
    /** The region dispatcher every chunk and entity task goes through; the plugin's scheduler by default. */
    private final RegionDispatcher dispatcher;
    /** world -> chunkKey -> (packed position -> display entity). Guarded by {@link #stateLock}. */
    private final Map<UUID, Map<Long, Map<Long, Entity>>> tracked = new HashMap<>();
    /** world -> chunk keys already scanned this session. Guarded by {@link #stateLock}. */
    private final Map<UUID, Set<Long>> scanned = new HashMap<>();
    /** Chunks still to scan, keyed by world and coordinates; drained by the maintenance task. */
    private final PendingChunkScanQueue<ChunkTarget> pending = new PendingChunkScanQueue<>();
    /**
     * Guards the tracking maps and the counters below.
     *
     * <p>World events arrive on the region thread that owns the affected chunk, while the maintenance task
     * and the enable/disable path run on the global thread, so every read and write of this state goes
     * through one lock. Nothing is dispatched and no entity is created while it is held: callers take what
     * they need under it and act outside it.
     */
    private final Object stateLock = new Object();

    private int liveCount;
    // Written by reload() on the global thread and read by region threads, so these stay volatile.
    private volatile int entitiesPerTick;
    private volatile int verifyPerTick;
    private volatile int scanChunksPerTick;
    private volatile int scanColumnHeight;
    private volatile int maxEntities = DEFAULT_MAX_ENTITIES;
    private int verifyCursor;
    private int createdThisTick;

    public CarrierRestorer(FarmersDelightPlugin plugin) {
        this(plugin, plugin.scheduler());
    }

    /** Test seam: the dispatcher is this class's only scheduler entry point, so a test can replace it. */
    CarrierRestorer(FarmersDelightPlugin plugin, RegionDispatcher dispatcher) {
        this.plugin = plugin;
        this.dispatcher = dispatcher;
        reload();
    }

    public void reload() {
        this.entitiesPerTick = Math.max(1, plugin.getConfigInt(DEFAULT_ENTITIES_PER_TICK,
                "performance.budgets.carrier-restore-entities-per-tick"));
        this.verifyPerTick = Math.max(1, plugin.getConfigInt(DEFAULT_VERIFY_PER_TICK,
                "performance.budgets.carrier-restore-verify-per-tick"));
        this.scanChunksPerTick = Math.max(1, plugin.getConfigInt(DEFAULT_SCAN_CHUNKS_PER_TICK,
                "performance.budgets.carrier-restore-scan-chunks-per-tick"));
        this.scanColumnHeight = Math.max(16, plugin.getConfigInt(DEFAULT_SCAN_COLUMN_HEIGHT,
                "performance.budgets.carrier-restore-scan-height"));
        this.maxEntities = Math.max(0, plugin.getConfigInt(DEFAULT_MAX_ENTITIES,
                "performance.carrier-restore-max-entities"));
        // Both display paths cull at one distance: the culler reads the same existing key the proxy manager
        // reads (no new setting), so a raised view-distance moves both. This reload() runs from the plugin's
        // reload chain and from this manager's own constructor, so startup is covered too.
        RealDisplayCuller.of(plugin).reload(ConfigSectionReader.optionalDouble(
                plugin.getConfig(), "performance.proxy-display.view-distance", 64.0D));
    }

    public boolean enabled() {
        return plugin.getConfigBoolean(true, "performance.restore-vanilla-carriers");
    }

    /** Display entities this class currently keeps alive; exposed for diagnostics and tests. */
    public int liveDisplays() {
        synchronized (stateLock) {
            return liveCount;
        }
    }

    /**
     * Registers one block, creating or dropping its display as the block data requires.
     *
     * <p>Region-bound: it reads the block and may spawn a display entity, so it must run on the thread that
     * owns the block's chunk. A neighbour handed over from a block event across a chunk border is left to
     * the region that owns it.
     *
     * <p>Called for every placed or state-changed block of a carrier material, which is the only way a
     * real vanilla block appears once a chunk is already resident.
     */
    public void update(Block block) {
        if (block == null) {
            return;
        }
        World world = block.getWorld();
        if (world == null) {
            return;
        }
        if (plugin.scheduler().isFolia() && !Bukkit.isOwnedByCurrentRegion(block)) {
            return;
        }
        // Physics events reach this with every neighbour of every moved block, so the material test runs
        // first: only the two carriers can be hijacked, and it avoids copying block data for the rest.
        Material material = block.getType();
        if (material != Material.CRIMSON_FENCE && material != Material.WARPED_FENCE_GATE) {
            return;
        }
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        long position = key(x, y, z);
        UUID worldId = world.getUID();
        // With the feature off no display is ever created, so the tracked maps are only consulted for a
        // block that already has one.
        if (!enabled()) {
            dropAt(worldId, x >> 4, z >> 4, position);
            return;
        }
        // Before CraftEngine has bound its blocks, every custom block still reads as a plain vanilla one,
        // so a rope fence placed during startup would be mistaken for a real crimson fence and get a second
        // copy of its model drawn over it. The chunk is scanned again once CraftEngine is up.
        if (!ItemUtils.isAnyCustomItemLoaded()) {
            return;
        }
        BlockData data = block.getBlockData();
        if (!isHijacked(data) || isCustomBlock(block)) {
            dropAt(worldId, x >> 4, z >> 4, position);
            return;
        }
        Map<Long, Entity> inChunk;
        synchronized (stateLock) {
            inChunk = chunkMap(world, x >> 4, z >> 4);
            Entity existing = inChunk.get(position);
            if (existing != null) {
                if (existing.isValid()) {
                    return;
                }
                // The chunk-unload sweep, a /fd cleanup or an external plugin removed it; rebuild below and
                // hand its place in the live count back, or the entity budget would drift upward.
                inChunk.remove(position);
                liveCount = Math.max(0, liveCount - 1);
            }
            if (liveCount >= maxEntities || createdThisTick >= entitiesPerTick) {
                return;
            }
            // Reserve the slot before creating, so the budget cannot be read stale by a second pass.
            liveCount++;
            createdThisTick++;
        }
        Entity created = create(world, x, y, z, data);
        boolean kept = false;
        if (created != null) {
            synchronized (stateLock) {
                // The chunk may have been dropped while the entity was being created; an untracked display
                // would never be cleaned up, so it is removed instead of being recorded.
                if (chunkAt(worldId, x >> 4, z >> 4) == inChunk) {
                    inChunk.put(position, created);
                    kept = true;
                }
            }
        }
        if (!kept) {
            synchronized (stateLock) {
                liveCount = Math.max(0, liveCount - 1);
                createdThisTick = Math.max(0, createdThisTick - 1);
            }
            removeDisplay(created);
        }
    }

    /**
     * Registers one block, handing the call to the region that owns it when this thread does not: a block
     * event reaches its neighbours across chunk borders, and a neighbour can belong to another region.
     */
    public void updateAcrossRegions(Block block) {
        if (block == null) {
            return;
        }
        World world = block.getWorld();
        if (world == null) {
            return;
        }
        if (!plugin.scheduler().isFolia() || Bukkit.isOwnedByCurrentRegion(block)) {
            update(block);
            return;
        }
        // Only the wrapper's coordinates are read here; the block itself is touched inside the region task.
        dispatcher.runAt(world, block.getX() >> 4, block.getZ() >> 4, () -> update(block));
    }

    /**
     * Drops the display at a position when its block changed or disappeared. Region-bound for the same
     * reason as {@link #update(Block)}: the display it removes is an entity in that chunk.
     */
    public void forget(Block block) {
        if (block == null || block.getWorld() == null) {
            return;
        }
        if (plugin.scheduler().isFolia() && !Bukkit.isOwnedByCurrentRegion(block)) {
            return;
        }
        World world = block.getWorld();
        dropAt(world.getUID(), block.getX() >> 4, block.getZ() >> 4,
                key(block.getX(), block.getY(), block.getZ()));
    }

    /**
     * Scans one resident chunk for affected vanilla blocks, limited to a configurable height band above
     * the world's minimum build height: that is where fences and gates in the borrowed states occur, and
     * a full column scan is far more expensive.
     *
     * <p>The positions are read from one snapshot of the chunk's block storage instead of a world lookup
     * per position: the copy costs one allocation for the whole chunk, while every world lookup allocates
     * a block and resolves the position through the chunk system again.
     *
     * <p>Region-bound: it reads the chunk and calls {@link #update(Block)}, which may spawn a display. Its
     * only caller is the region task the maintenance pass dispatches, and that task has already checked that
     * the chunk is loaded.
     *
     * <p>Each chunk is scanned once between loads; the events that create or change a carrier block keep
     * the result current afterwards, so a repeat scan buys nothing.
     */
    public void scanChunk(World world, int chunkX, int chunkZ) {
        if (world == null || !enabled()) {
            return;
        }
        long position = chunkKey(chunkX, chunkZ);
        synchronized (stateLock) {
            if (!scanned.computeIfAbsent(world.getUID(), key -> new HashSet<>()).add(position)) {
                return;
            }
        }
        int minY = world.getMinHeight();
        int yLength = scanYLength(scanColumnHeight, minY, world.getMaxHeight());
        if (yLength <= 0) {
            return;
        }
        ChunkSnapshot snapshot;
        try {
            snapshot = world.getChunkAt(chunkX, chunkZ).getChunkSnapshot(false, false, false, false);
        } catch (RuntimeException failure) {
            // Leave the chunk unmarked so its next load queues it again. Letting this escape would cancel
            // the maintenance task that drives every scan, which is worse than one unscanned chunk.
            unmarkScanned(world, position);
            return;
        }
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        for (int yOffset = 0; yOffset < yLength; yOffset++) {
            // The budget is checked once per section rather than per position: update() enforces it again
            // for every block it would create a display for, so this only ends the walk early.
            if (isBudgetExhausted()) {
                return;
            }
            int y = minY + yOffset;
            // A section that holds only air holds neither carrier material, so skipping it cannot change
            // which blocks this scan reports.
            if (snapshot.isSectionEmpty(yOffset >> 4)) {
                continue;
            }
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    Material type = snapshot.getBlockType(x, y, z);
                    if (type == Material.CRIMSON_FENCE || type == Material.WARPED_FENCE_GATE) {
                        update(world.getBlockAt(baseX + x, y, baseZ + z));
                    }
                }
            }
        }
    }

    /**
     * Height of the scanned band: the configured column height, clamped to the world's own build range so
     * a world shorter than the band never walks past its ceiling.
     */
    static int scanYLength(int scanColumnHeight, int worldMinHeight, int worldMaxHeight) {
        int maxY = Math.min(worldMaxHeight, worldMinHeight + scanColumnHeight);
        return Math.max(0, maxY - worldMinHeight);
    }

    /**
     * Block positions one full chunk scan visits: the 16x16 column of the scanned band. Kept next to the
     * band calculation so the per-chunk cost is a checked number rather than a comment.
     */
    static int positionsPerChunk(int scanColumnHeight, int worldMinHeight, int worldMaxHeight) {
        return 16 * 16 * scanYLength(scanColumnHeight, worldMinHeight, worldMaxHeight);
    }

    /**
     * Queues a chunk for a background scan. Scanning on the chunk-load event itself would put a full
     * column walk on the chunk's critical path; the maintenance task drains the queue in small slices.
     *
     * <p>Only the world handle and the coordinates are kept: a Chunk belongs to the region that owns it and
     * is not handed to another thread. A chunk that is already queued keeps its place and only has its entry
     * replaced, so a burst of events naming the same chunk leaves one scan of it.
     */
    public void queueChunkScan(World world, int chunkX, int chunkZ) {
        if (world != null && enabled()) {
            pending.add(world.getUID(), chunkX, chunkZ, new ChunkTarget(world, chunkX, chunkZ));
        }
    }

    /**
     * Fills the scan queue with the chunks that are already resident, so a plugin enable (or a reload)
     * still reaches blocks that no place or physics event will ever report.
     *
     * <p>Only a platform whose loaded chunks may be enumerated from this thread does this; elsewhere the
     * queue is filled by chunk load events alone. See {@link #mayEnumerateLoadedChunks(boolean)}.
     */
    public void prepareStartup() {
        if (!mayEnumerateLoadedChunks(plugin.scheduler().isFolia())) {
            return;
        }
        for (World world : plugin.getServer().getWorlds()) {
            UUID worldId = world.getUID();
            for (Chunk chunk : world.getLoadedChunks()) {
                pending.add(worldId, chunk.getX(), chunk.getZ(),
                        new ChunkTarget(world, chunk.getX(), chunk.getZ()));
            }
        }
    }

    /**
     * Whether the loaded chunks of every world may be enumerated from the thread this runs on.
     *
     * <p>On Folia they may not: the enumeration is a world-wide read that no single region owns, and the
     * chunks it returns belong to regions this thread does not hold, so they must not be touched. There is
     * no region-scoped replacement for "every loaded chunk", so there the queue is filled by the chunk load
     * event instead, and a chunk that was already resident when the plugin enabled is covered once it
     * reloads.
     */
    static boolean mayEnumerateLoadedChunks(boolean folia) {
        return !folia;
    }

    /** Drops every display of an unloading chunk; the displays are non-persistent, so none survives it. */
    public void unloadChunk(Chunk chunk) {
        if (chunk == null) {
            return;
        }
        World world = chunk.getWorld();
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();
        UUID worldId = world.getUID();
        long position = chunkKey(chunkX, chunkZ);
        Map<Long, Entity> inChunk;
        synchronized (stateLock) {
            Map<Long, Map<Long, Entity>> chunks = tracked.get(worldId);
            inChunk = chunks == null ? null : chunks.remove(position);
            // The chunk is scanned again after it reloads; a stale "already scanned" mark would leave any
            // carrier block that a later load introduces unhandled.
            Set<Long> scannedInWorld = scanned.get(worldId);
            if (scannedInWorld != null) {
                scannedInWorld.remove(position);
            }
        }
        dropAll(inChunk);
        pending.remove(worldId, chunkX, chunkZ);
    }

    /** Drops every display of a world that is going away. */
    public void unloadWorld(World world) {
        if (world == null) {
            return;
        }
        UUID worldId = world.getUID();
        Map<Long, Map<Long, Entity>> chunks;
        synchronized (stateLock) {
            chunks = tracked.remove(worldId);
            scanned.remove(worldId);
        }
        if (chunks != null) {
            for (Map<Long, Entity> inChunk : chunks.values()) {
                dropAll(inChunk);
            }
        }
        pending.removeWorld(worldId);
    }

    /**
     * Removes displays left behind by a previous session. They are spawned non-persistent, so a clean
     * shutdown leaves none; a crash or a force-stop can, and this is the only path that finds them.
     *
     * <p>Paper only: the scan asks the world for every display it holds, which is a world-wide read that no
     * single region owns on Folia. There the displays are in any case non-persistent, so they never survive
     * a restart, and the ones this session created are dropped by the per-chunk paths.
     */
    public void sweepOrphans(World world) {
        if (world == null || plugin.scheduler().isFolia()) {
            return;
        }
        for (BlockDisplay display : world.getEntitiesByClass(BlockDisplay.class)) {
            Byte marker = display.getPersistentDataContainer().get(KIND, PersistentDataType.BYTE);
            if (marker != null) {
                removeDisplay(display);
            }
        }
    }

    /**
     * Throttled maintenance: hands a slice of the queued chunk scans to the region that owns each chunk,
     * then a slice of the tracked displays to the region that owns each entity.
     *
     * <p>It runs on the global thread and reads neither block nor entity data itself, which is what makes it
     * legal there; the work always happens on the owning region.
     */
    public void tick() {
        synchronized (stateLock) {
            createdThisTick = 0;
        }
        if (!enabled()) {
            return;
        }
        dispatchQueuedScans();
        dispatchVerification();
    }

    /** Drops every tracked display and forgets the session state; each removal goes to its entity's region. */
    public void shutdown() {
        List<Entity> displays = new ArrayList<>();
        synchronized (stateLock) {
            for (Map<Long, Map<Long, Entity>> byChunk : tracked.values()) {
                for (Map<Long, Entity> inChunk : byChunk.values()) {
                    displays.addAll(inChunk.values());
                }
            }
            tracked.clear();
            scanned.clear();
            liveCount = 0;
            verifyCursor = 0;
            createdThisTick = 0;
        }
        pending.clear();
        // Disable path: without this a disable/enable in the same JVM would hand back the cached culler whose
        // task is already cancelled, and culling would silently stop (no exception, no log).
        RealDisplayCuller.of(plugin).shutdown();
        // Displays are non-persistent, so one whose removal cannot be dispatched this late costs an entity
        // only until its world unloads; blocking the disable path on it would not pay for itself.
        for (Entity display : displays) {
            removeDisplay(display);
        }
    }

    /**
     * Hands a rotating slice of the tracked displays to the region that owns each one, which drops the ones
     * that are gone (chunk sweep, plugin cleanup, external removal).
     */
    private void dispatchVerification() {
        List<Tracked> slice;
        synchronized (stateLock) {
            SweepWindow window = verificationWindow(trackedCount(), verifyCursor, verifyPerTick);
            verifyCursor = window.nextCursor();
            slice = takeVerificationEntries(window.start(), window.count());
        }
        for (Tracked tracked : slice) {
            Entity entity = tracked.entity();
            // Validity is entity data, so it is read on the entity's own region. The retired callback covers
            // an entity that is gone before the task can run, so its entry never survives it.
            dispatcher.runForEntity(entity, () -> verifyDisplay(tracked), () -> dropTrackedEntry(tracked));
        }
    }

    /**
     * Hands each queued chunk of this slice to its own region; the scan itself never runs on this thread.
     */
    private void dispatchQueuedScans() {
        dispatchQueuedScans(pending, scanChunksPerTick, dispatcher, this::scanChunk);
    }

    /**
     * Takes at most {@code budget} queued chunks, in insertion order, and hands each to the region that owns
     * it. The chunk is re-checked inside the dispatched task, so one that unloaded while it waited is skipped
     * rather than scanned from stale state.
     */
    static void dispatchQueuedScans(PendingChunkScanQueue<ChunkTarget> pending, int budget,
                                    RegionDispatcher dispatcher, ChunkScanTask scanTask) {
        for (ChunkTarget target : pending.drain(budget)) {
            RegionTasks.runAtLoadedChunk(dispatcher, target.world(), target.chunkX(), target.chunkZ(),
                    () -> scanTask.scan(target.world(), target.chunkX(), target.chunkZ()));
        }
    }

    /** The body a queued chunk scan runs on the region that owns the chunk. */
    interface ChunkScanTask {
        void scan(World world, int chunkX, int chunkZ);
    }

    /** Drops the record of a display that no longer exists; runs on the entity's own region. */
    private void verifyDisplay(Tracked tracked) {
        if (!tracked.entity().isValid()) {
            dropTrackedEntry(tracked);
        }
    }

    /**
     * The rotating window of tracked entries one verification pass covers: {@code start} is the first entry
     * index and {@code count} how many it visits. The cursor is reduced modulo the tracked count so it
     * survives a set that shrank, and an over-large cursor restarts at the beginning.
     */
    static SweepWindow verificationWindow(int total, int cursor, int perTick) {
        int bounded = total <= 0 ? 0 : Math.floorMod(cursor, total);
        return SweepWindow.of(total, bounded, perTick);
    }

    /** Number of displays currently recorded, across all worlds and chunks. Caller holds {@link #stateLock}. */
    private int trackedCount() {
        int total = 0;
        for (Map<Long, Map<Long, Entity>> byChunk : tracked.values()) {
            for (Map<Long, Entity> inChunk : byChunk.values()) {
                total += inChunk.size();
            }
        }
        return total;
    }

    /**
     * Copies up to {@code count} tracked entries starting at {@code start} in iteration order. Only the
     * entries this pass verifies are materialised, so a large tracked set does not allocate a full snapshot
     * on every pass. Caller holds {@link #stateLock}.
     */
    private List<Tracked> takeVerificationEntries(int start, int count) {
        if (count <= 0) {
            return List.of();
        }
        List<Tracked> slice = new ArrayList<>(count);
        int index = 0;
        for (Map<Long, Map<Long, Entity>> byChunk : tracked.values()) {
            for (Map<Long, Entity> inChunk : byChunk.values()) {
                for (Map.Entry<Long, Entity> entry : inChunk.entrySet()) {
                    if (index++ < start) {
                        continue;
                    }
                    slice.add(new Tracked(inChunk, entry.getKey(), entry.getValue()));
                    if (slice.size() >= count) {
                        return slice;
                    }
                }
            }
        }
        return slice;
    }

    private Entity create(World world, int x, int y, int z, BlockData data) {
        Location location = new Location(world, x, y, z);
        try {
            Entity spawned = world.spawn(location, BlockDisplay.class, entity -> {
                entity.setPersistent(false);
                entity.setSilent(true);
                entity.setGravity(false);
                entity.setInvulnerable(true);
                entity.setViewRange(1.0F);
                entity.setInterpolationDuration(0);
                entity.setTeleportDuration(0);
                entity.setTransformation(new Transformation(
                        new Vector3f(0.0F, 0.0F, 0.0F),
                        new Quaternionf(),
                        new Vector3f(1.0F, 1.0F, 1.0F),
                        new Quaternionf()));
                entity.setBlock(data.clone());
                entity.getPersistentDataContainer().set(KIND, PersistentDataType.BYTE, (byte) 1);
            });
            RealDisplayCuller.of(plugin).track(spawned);
            return spawned;
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    /** The display map of one chunk, created on demand. Caller holds {@link #stateLock}. */
    private Map<Long, Entity> chunkMap(World world, int chunkX, int chunkZ) {
        return tracked.computeIfAbsent(world.getUID(), key -> new HashMap<>())
                .computeIfAbsent(chunkKey(chunkX, chunkZ), key -> new HashMap<>());
    }

    /** The display map of one chunk, or null when that chunk has none. Caller holds {@link #stateLock}. */
    private Map<Long, Entity> chunkAt(UUID worldId, int chunkX, int chunkZ) {
        Map<Long, Map<Long, Entity>> chunks = tracked.get(worldId);
        return chunks == null ? null : chunks.get(chunkKey(chunkX, chunkZ));
    }

    /** Drops one tracked display by chunk and packed position; a no-op when that chunk holds none. */
    private void dropAt(UUID worldId, int chunkX, int chunkZ, long position) {
        Entity display;
        synchronized (stateLock) {
            Map<Long, Entity> inChunk = chunkAt(worldId, chunkX, chunkZ);
            if (inChunk == null) {
                return;
            }
            display = inChunk.remove(position);
            if (display != null) {
                liveCount = Math.max(0, liveCount - 1);
            }
        }
        removeDisplay(display);
    }

    /**
     * Drops every recorded display of one chunk: the accounting happens under the state lock, the removals
     * happen outside it and go to the region that owns each entity.
     */
    private void dropAll(Map<Long, Entity> inChunk) {
        if (inChunk == null) {
            return;
        }
        List<Entity> displays;
        synchronized (stateLock) {
            if (inChunk.isEmpty()) {
                return;
            }
            displays = new ArrayList<>(inChunk.values());
            inChunk.clear();
            liveCount = Math.max(0, liveCount - displays.size());
        }
        for (Entity display : displays) {
            removeDisplay(display);
        }
    }

    /** Removes one tracked entry and its share of the live count. */
    private void dropTrackedEntry(Tracked tracked) {
        synchronized (stateLock) {
            if (tracked.owner().remove(tracked.position()) != null) {
                liveCount = Math.max(0, liveCount - 1);
            }
        }
    }

    /**
     * Removes one display on the region that owns it, inline when this thread already owns it. A display is
     * non-persistent, so one whose removal cannot be dispatched is covered by its world unloading.
     */
    private void removeDisplay(Entity entity) {
        if (entity == null) {
            return;
        }
        if (!plugin.scheduler().isFolia() || Bukkit.isOwnedByCurrentRegion(entity)) {
            removeDisplayNow(entity);
            return;
        }
        dispatcher.runForEntity(entity, () -> removeDisplayNow(entity));
    }

    private static void removeDisplayNow(Entity entity) {
        if (entity != null && entity.isValid()) {
            entity.remove();
        }
    }

    /** True when no further display may be created this session. */
    private boolean isBudgetExhausted() {
        synchronized (stateLock) {
            return liveCount >= maxEntities;
        }
    }

    /** Clears the scanned mark of one chunk, so its next load queues it again. */
    private void unmarkScanned(World world, long position) {
        synchronized (stateLock) {
            Set<Long> marks = scanned.get(world.getUID());
            if (marks != null) {
                marks.remove(position);
            }
        }
    }

    private static boolean isCustomBlock(Block block) {
        // A custom block borrows the same vanilla state, but CraftEngine already draws it; only the real
        // vanilla block needs a display. The resident check never loads a neighbouring chunk.
        return CustomBlockUtils.getStateIfResident(block) != null;
    }

    private static boolean isHijacked(BlockData data) {
        return HIJACKED.contains(data.getAsString(true));
    }

    private static Set<String> buildHijackedStates() {
        Set<String> states = new HashSet<>(32);
        for (boolean north : BOOLEANS) {
            for (boolean east : BOOLEANS) {
                for (boolean south : BOOLEANS) {
                    for (boolean west : BOOLEANS) {
                        states.add("minecraft:crimson_fence[east=" + east + ",north=" + north
                                + ",south=" + south + ",waterlogged=true,west=" + west + "]");
                    }
                }
            }
        }
        for (String facing : new String[]{"north", "east", "south", "west"}) {
            for (boolean inWall : BOOLEANS) {
                for (boolean open : BOOLEANS) {
                    states.add("minecraft:warped_fence_gate[facing=" + facing + ",in_wall=" + inWall
                            + ",open=" + open + ",powered=true]");
                }
            }
        }
        return Set.copyOf(states);
    }

    /**
     * Packs a block position into one long. The layout matches the one the rest of the plugin uses for
     * chunk keys (26 bits of x, 26 of z, 12 of y) so positions never collide inside or across chunks.
     */
    static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /** One chunk waiting for a scan: a world handle plus coordinates, never a Chunk across threads. */
    record ChunkTarget(World world, int chunkX, int chunkZ) {
    }

    private record Tracked(Map<Long, Entity> owner, long position, Entity entity) {
    }
}
