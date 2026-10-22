package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.util.scheduler.RegionDispatcher;
import com.huidu.farmersdelight.util.scheduler.RegionTasks;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Insertion-ordered, deduplicated queue of per-chunk work items, keyed by world and chunk coordinates.
 *
 * @param <T> payload type; this class never inspects it
 */
final class PendingChunkScanQueue<T> {

    private record Key(UUID worldId, int chunkX, int chunkZ) {
    }

    private final Map<Key, T> entries = new LinkedHashMap<>();
    /** Adds and drops arrive from world events while the maintenance task drains, so the map is guarded. */
    private final Object lock = new Object();

    /** Queues a payload, or replaces the payload already queued for that chunk without moving it. */
    void add(UUID worldId, int chunkX, int chunkZ, T payload) {
        if (worldId == null || payload == null) {
            return;
        }
        synchronized (lock) {
            entries.put(new Key(worldId, chunkX, chunkZ), payload);
        }
    }

    /** Drops the queued chunk, if any, and reports whether an entry was removed. */
    boolean remove(UUID worldId, int chunkX, int chunkZ) {
        if (worldId == null) {
            return false;
        }
        synchronized (lock) {
            return entries.remove(new Key(worldId, chunkX, chunkZ)) != null;
        }
    }

    /**
     * Drops every queued chunk of one world and returns how many were dropped. Only a world unload uses
     * this, so the single walk over the queue is paid once per world instead of once per chunk.
     */
    int removeWorld(UUID worldId) {
        if (worldId == null) {
            return 0;
        }
        synchronized (lock) {
            int before = entries.size();
            entries.keySet().removeIf(key -> key.worldId().equals(worldId));
            return before - entries.size();
        }
    }

    /** Removes and returns up to limit payloads, in insertion order. */
    List<T> drain(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        synchronized (lock) {
            if (entries.isEmpty()) {
                return List.of();
            }
            List<T> drained = new ArrayList<>(Math.min(limit, entries.size()));
            Iterator<Map.Entry<Key, T>> iterator = entries.entrySet().iterator();
            while (iterator.hasNext() && drained.size() < limit) {
                drained.add(iterator.next().getValue());
                iterator.remove();
            }
            return drained;
        }
    }

    int size() {
        synchronized (lock) {
            return entries.size();
        }
    }

    boolean isEmpty() {
        synchronized (lock) {
            return entries.isEmpty();
        }
    }

    void clear() {
        synchronized (lock) {
            entries.clear();
        }
    }

    /** One chunk waiting for a scan: a world handle plus coordinates, never a Chunk across threads. */
    record ChunkTarget(World world, int chunkX, int chunkZ) {
    }

    /** The body a queued chunk scan runs on the region that owns the chunk. */
    interface ChunkScanTask {
        void scan(World world, int chunkX, int chunkZ);
    }

    /**
     * Takes at most budget queued chunks, in insertion order, and hands each to the region that owns it.
     * The chunk is re-checked inside the dispatched task, so one that unloaded while it waited is skipped
     * rather than scanned from stale state.
     */
    static void dispatchQueuedScans(PendingChunkScanQueue<ChunkTarget> pending, int budget,
                                    RegionDispatcher dispatcher, ChunkScanTask scanTask) {
        for (ChunkTarget target : pending.drain(budget)) {
            RegionTasks.runAtLoadedChunk(dispatcher, target.world(), target.chunkX(), target.chunkZ(),
                    () -> scanTask.scan(target.world(), target.chunkX(), target.chunkZ()));
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
}
