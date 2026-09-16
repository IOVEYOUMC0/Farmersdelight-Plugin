package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.util.ManagerSupport;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Concurrent location index for world and chunk scoped cleanup. */
final class WorldChunkLocationIndex {

    private final Map<UUID, Set<Location>> byWorld = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Long, Set<Location>>> byChunk = new ConcurrentHashMap<>();

    void add(Location location) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        World world = location.getWorld();
        UUID worldId = world.getUID();
        long chunkKey = ManagerSupport.chunkKey(location);
        // Insert inside compute so the bucket write is atomic with respect to remove()'s drop-if-empty.
        // computeIfAbsent(..).add(..) would publish the set, release the bin lock, and only then add —
        // long enough for a concurrent remove to see an empty set and discard the whole mapping.
        byWorld.compute(worldId, (ignored, locations) -> {
            Set<Location> target = locations != null ? locations : ConcurrentHashMap.newKeySet();
            target.add(location);
            return target;
        });
        byChunk.compute(worldId, (ignored, chunks) -> {
            Map<Long, Set<Location>> target = chunks != null ? chunks : new ConcurrentHashMap<>();
            target.compute(chunkKey, (key, locations) -> {
                Set<Location> set = locations != null ? locations : ConcurrentHashMap.newKeySet();
                set.add(location);
                return set;
            });
            return target;
        });
    }

    void remove(Location location) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        UUID worldId = location.getWorld().getUID();
        long chunkKey = ManagerSupport.chunkKey(location);
        // Removal and the emptiness check share the bin lock, so a concurrent add cannot have its entry
        // discarded along with the mapping.
        byWorld.computeIfPresent(worldId, (ignored, locations) -> {
            locations.remove(location);
            return locations.isEmpty() ? null : locations;
        });
        byChunk.computeIfPresent(worldId, (ignored, chunks) -> {
            chunks.computeIfPresent(chunkKey, (key, locations) -> {
                locations.remove(location);
                return locations.isEmpty() ? null : locations;
            });
            return chunks.isEmpty() ? null : chunks;
        });
    }

    List<Location> worldLocations(UUID worldId) {
        return snapshot(byWorld.get(worldId));
    }

    List<Location> chunkLocations(UUID worldId, long chunkKey) {
        Map<Long, Set<Location>> worldChunks = byChunk.get(worldId);
        return worldChunks == null ? List.of() : snapshot(worldChunks.get(chunkKey));
    }

    List<Location> removeWorld(UUID worldId) {
        byChunk.remove(worldId);
        return snapshot(byWorld.remove(worldId));
    }

    void clear() {
        byWorld.clear();
        byChunk.clear();
    }

    private static List<Location> snapshot(Set<Location> locations) {
        return locations == null || locations.isEmpty() ? List.of() : List.copyOf(locations);
    }
}
