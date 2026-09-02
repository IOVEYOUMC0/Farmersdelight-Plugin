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
        byWorld.computeIfAbsent(worldId, ignored -> ConcurrentHashMap.newKeySet()).add(location);
        byChunk.computeIfAbsent(worldId, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(ManagerSupport.chunkKey(location), ignored -> ConcurrentHashMap.newKeySet())
                .add(location);
    }

    void remove(Location location) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        UUID worldId = location.getWorld().getUID();
        Set<Location> worldLocations = byWorld.get(worldId);
        if (worldLocations != null) {
            worldLocations.remove(location);
            if (worldLocations.isEmpty()) {
                byWorld.remove(worldId, worldLocations);
            }
        }

        Map<Long, Set<Location>> worldChunks = byChunk.get(worldId);
        if (worldChunks == null) {
            return;
        }
        long chunkKey = ManagerSupport.chunkKey(location);
        Set<Location> chunkLocations = worldChunks.get(chunkKey);
        if (chunkLocations != null) {
            chunkLocations.remove(location);
            if (chunkLocations.isEmpty()) {
                worldChunks.remove(chunkKey, chunkLocations);
            }
        }
        if (worldChunks.isEmpty()) {
            byChunk.remove(worldId, worldChunks);
        }
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
