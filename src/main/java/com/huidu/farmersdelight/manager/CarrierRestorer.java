package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.util.CustomBlockUtils;
import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.Chunk;
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
    public static final NamespacedKey KIND = new NamespacedKey("farmersdelight", "rope_carrier_restore");

    private static final boolean[] BOOLEANS = {false, true};

    /** Every carrier used by farmersdelight:rope_fence and farmersdelight:rope_fence_gate. */
    private static final Set<String> HIJACKED = buildHijackedStates();

    private static final int DEFAULT_MAX_ENTITIES = 4096;
    private static final int DEFAULT_ENTITIES_PER_TICK = 8;
    private static final int DEFAULT_VERIFY_PER_TICK = 64;
    private static final int DEFAULT_SCAN_CHUNKS_PER_TICK = 4;
    private static final int DEFAULT_SCAN_COLUMN_HEIGHT = 96;

    private final FarmersDelightPlugin plugin;
    /** world -> chunkKey -> (packed position -> display entity). */
    private final Map<UUID, Map<Long, Map<Long, Entity>>> tracked = new HashMap<>();
    /** world -> chunk keys already scanned this session. */
    private final Map<UUID, Set<Long>> scanned = new HashMap<>();
    /** Chunks still to scan; filled at startup and by chunk load, drained by the maintenance task. */
    private final Deque<Chunk> pending = new ArrayDeque<>();

    private int liveCount;
    private int entitiesPerTick;
    private int verifyPerTick;
    private int scanChunksPerTick;
    private int scanColumnHeight;
    private int maxEntities = DEFAULT_MAX_ENTITIES;
    private long verifyCursor;
    private int createdThisTick;

    public CarrierRestorer(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
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
    }

    public boolean enabled() {
        return plugin.getConfigBoolean(true, "performance.restore-vanilla-carriers");
    }

    /** Display entities this class currently keeps alive; exposed for diagnostics and tests. */
    public int liveDisplays() {
        return liveCount;
    }

    /**
     * Registers one block, creating or dropping its display as the block data requires.
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
        // With the feature off no display is ever created, so the tracked maps are not even touched: a
        // crimson forest would otherwise allocate an entry per fence block for nothing.
        if (!enabled()) {
            Map<Long, Entity> disabled = tracked.containsKey(world.getUID())
                    ? chunkMap(world, x >> 4, z >> 4)
                    : null;
            if (disabled != null && disabled.containsKey(position)) {
                drop(disabled, position, disabled.get(position));
            }
            return;
        }
        // Before CraftEngine has bound its blocks, every custom block still reads as a plain vanilla one,
        // so a rope fence placed during startup would be mistaken for a real crimson fence and get a second
        // copy of its model drawn over it. The chunk is scanned again once CraftEngine is up.
        if (!ItemUtils.isAnyCustomItemLoaded()) {
            return;
        }
        boolean wanted = isHijacked(block.getBlockData()) && !isCustomBlock(block);
        if (!wanted) {
            Map<Long, Entity> existingChunk = tracked.containsKey(world.getUID())
                    ? chunkMap(world, x >> 4, z >> 4)
                    : null;
            if (existingChunk != null && existingChunk.containsKey(position)) {
                drop(existingChunk, position, existingChunk.get(position));
            }
            return;
        }
        Map<Long, Entity> inChunk = chunkMap(world, x >> 4, z >> 4);
        Entity existing = inChunk.get(position);
        if (existing != null) {
            if (existing.isValid()) {
                return;
            }
            // The chunk-unload sweep, a /fd cleanup or an external plugin removed it; rebuild below.
            inChunk.remove(position);
        }
        if (liveCount >= maxEntities || createdThisTick >= entitiesPerTick) {
            return;
        }
        Entity created = create(world, x, y, z, block.getBlockData());
        if (created != null) {
            inChunk.put(position, created);
            liveCount++;
            createdThisTick++;
        }
    }

    /** Drops the display at a position when its block changed or disappeared. */
    public void forget(Block block) {
        if (block == null || block.getWorld() == null) {
            return;
        }
        World world = block.getWorld();
        Map<Long, Map<Long, Entity>> chunks = tracked.get(world.getUID());
        Map<Long, Entity> inChunk = chunks == null
                ? null
                : chunks.get(chunkKey(block.getX() >> 4, block.getZ() >> 4));
        if (inChunk != null) {
            long position = key(block.getX(), block.getY(), block.getZ());
            drop(inChunk, position, inChunk.get(position));
        }
    }

    /**
     * Scans one resident chunk for affected vanilla blocks, limited to a configurable height band above
     * the world's minimum build height: that is where fences and gates in the borrowed states occur, and
     * a full column scan is far more expensive.
     *
     * <p>Each chunk is scanned once between loads; the events that create or change a carrier block keep
     * the result current afterwards, so a repeat scan buys nothing.
     */
    public void scanChunk(Chunk chunk, boolean force) {
        if (chunk == null) {
            return;
        }
        World world = chunk.getWorld();
        long position = chunkKey(chunk.getX(), chunk.getZ());
        if (!force && !scanned.computeIfAbsent(world.getUID(), key -> new HashSet<>()).add(position)) {
            return;
        }
        if (!enabled()) {
            return;
        }
        int minX = chunk.getX() << 4;
        int minZ = chunk.getZ() << 4;
        int minY = world.getMinHeight();
        int maxY = Math.min(world.getMaxHeight(), minY + scanColumnHeight);
        for (int y = minY; y < maxY; y++) {
            for (int x = minX; x < minX + 16; x++) {
                for (int z = minZ; z < minZ + 16; z++) {
                    if (liveCount >= maxEntities) {
                        return;
                    }
                    Block block = world.getBlockAt(x, y, z);
                    Material type = block.getType();
                    if (type == Material.CRIMSON_FENCE || type == Material.WARPED_FENCE_GATE) {
                        update(block);
                    }
                }
            }
        }
    }

    /**
     * Queues a chunk for a background scan. Scanning on the chunk-load event itself would put a full
     * column walk on the chunk's critical path; the maintenance task drains the queue in small slices.
     */
    public void queueChunkScan(Chunk chunk) {
        if (chunk != null && enabled()) {
            pending.add(chunk);
        }
    }

    /**
     * Fills the scan queue with the chunks that are already resident, so a plugin enable (or a reload)
     * still reaches blocks that no place or physics event will ever report.
     */
    public void prepareStartup() {
        for (World world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                pending.add(chunk);
            }
        }
    }

    /** Drops every display of an unloading chunk; the displays are non-persistent, so none survives it. */
    public void unloadChunk(Chunk chunk) {
        if (chunk == null) {
            return;
        }
        long position = chunkKey(chunk.getX(), chunk.getZ());
        Map<Long, Map<Long, Entity>> chunks = tracked.get(chunk.getWorld().getUID());
        if (chunks != null) {
            dropAll(chunks.remove(position));
        }
        // The chunk is scanned again after it reloads; a stale "already scanned" mark would leave any
        // carrier block that a later load introduces unhandled.
        Set<Long> scannedInWorld = scanned.get(chunk.getWorld().getUID());
        if (scannedInWorld != null) {
            scannedInWorld.remove(position);
        }
        pending.removeIf(queued -> queued.getWorld().getUID().equals(chunk.getWorld().getUID())
                && queued.getX() == chunk.getX() && queued.getZ() == chunk.getZ());
    }

    /** Drops every display of a world that is going away. */
    public void unloadWorld(World world) {
        if (world == null) {
            return;
        }
        UUID worldId = world.getUID();
        Map<Long, Map<Long, Entity>> chunks = tracked.remove(worldId);
        if (chunks != null) {
            for (Map<Long, Entity> inChunk : chunks.values()) {
                dropAll(inChunk);
            }
        }
        scanned.remove(worldId);
        pending.removeIf(chunk -> chunk.getWorld().getUID().equals(worldId));
    }

    /**
     * Removes displays left behind by a previous session. They are spawned non-persistent, so a clean
     * shutdown leaves none; a crash or a force-stop can, and this is the only path that finds them.
     */
    public void sweepOrphans(World world) {
        if (world == null) {
            return;
        }
        for (BlockDisplay display : world.getEntitiesByClass(BlockDisplay.class)) {
            Byte marker = display.getPersistentDataContainer().get(KIND, PersistentDataType.BYTE);
            if (marker != null) {
                display.remove();
            }
        }
    }

    /**
     * Throttled maintenance: scans a slice of the queued chunks and verifies a slice of the tracked
     * positions. Both slices stay small so a large world is covered without a burst of work.
     */
    public void tick() {
        createdThisTick = 0;
        if (!enabled()) {
            return;
        }
        scanSlice();
        verifySlice();
    }

    public void shutdown() {
        for (Map<Long, Map<Long, Entity>> chunks : tracked.values()) {
            for (Map<Long, Entity> inChunk : chunks.values()) {
                dropAll(inChunk);
            }
        }
        tracked.clear();
        scanned.clear();
        pending.clear();
        liveCount = 0;
    }

    /**
     * Verifies a slice of the tracked positions: a position whose display died (chunk sweep, plugin
     * cleanup, external removal) or whose block no longer needs a display is dropped or rebuilt.
     */
    private void verifySlice() {
        List<Tracked> snapshot = new ArrayList<>();
        for (Map<Long, Map<Long, Entity>> chunks : tracked.values()) {
            for (Map<Long, Entity> inChunk : chunks.values()) {
                for (Map.Entry<Long, Entity> entry : inChunk.entrySet()) {
                    snapshot.add(new Tracked(inChunk, entry.getKey(), entry.getValue()));
                }
            }
        }
        if (snapshot.isEmpty()) {
            return;
        }
        int limit = Math.min(verifyPerTick, snapshot.size());
        for (int i = 0; i < limit; i++) {
            Tracked entry = snapshot.get((int) Math.floorMod(verifyCursor + i, snapshot.size()));
            if (entry.entity().isValid()) {
                continue;
            }
            if (entry.owner().remove(entry.position()) != null) {
                liveCount = Math.max(0, liveCount - 1);
            }
        }
        verifyCursor += limit;
    }

    /** Scans the queued chunks, a slice per tick, then verifies a slice of the tracked positions. */
    private void scanSlice() {
        for (int i = 0; i < scanChunksPerTick; i++) {
            Chunk chunk = pending.poll();
            if (chunk == null) {
                return;
            }
            if (!chunk.isLoaded()) {
                continue;
            }
            scanChunk(chunk, false);
        }
    }

    private Entity create(World world, int x, int y, int z, BlockData data) {
        Location location = new Location(world, x, y, z);
        try {
            return world.spawn(location, BlockDisplay.class, entity -> {
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
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private Map<Long, Entity> chunkMap(World world, int chunkX, int chunkZ) {
        return tracked.computeIfAbsent(world.getUID(), key -> new HashMap<>())
                .computeIfAbsent(chunkKey(chunkX, chunkZ), key -> new HashMap<>());
    }

    private void drop(Map<Long, Entity> inChunk, long position, Entity entity) {
        if (inChunk == null) {
            return;
        }
        if (entity == null) {
            inChunk.remove(position);
            return;
        }
        if (inChunk.remove(position) != null) {
            removeEntity(entity);
            liveCount = Math.max(0, liveCount - 1);
        }
    }

    private void dropAll(Map<Long, Entity> inChunk) {
        if (inChunk == null) {
            return;
        }
        for (Entity entity : inChunk.values()) {
            removeEntity(entity);
        }
        liveCount = Math.max(0, liveCount - inChunk.size());
        inChunk.clear();
    }

    private static void removeEntity(Entity entity) {
        if (entity != null && entity.isValid()) {
            entity.remove();
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

    private record Tracked(Map<Long, Entity> owner, long position, Entity entity) {
    }
}
