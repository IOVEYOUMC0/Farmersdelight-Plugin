package com.huidu.farmersdelight.manager;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldChunkLocationIndexTest {

    @Test
    void tracksAndRemovesLocationsByWorldAndChunk() {
        WorldChunkLocationIndex index = new WorldChunkLocationIndex();
        UUID firstWorldId = UUID.randomUUID();
        World firstWorld = world(firstWorldId);
        Location first = new Location(firstWorld, 1, 64, 1);
        Location second = new Location(firstWorld, 20, 64, 1);

        index.add(first);
        index.add(second);

        assertEquals(2, index.worldLocations(firstWorldId).size());
        assertTrue(index.chunkLocations(firstWorldId, 0L).contains(first));
        assertTrue(index.chunkLocations(firstWorldId, 1L << 32).contains(second));

        index.remove(first);

        assertFalse(index.worldLocations(firstWorldId).contains(first));
        assertTrue(index.worldLocations(firstWorldId).contains(second));
        assertTrue(index.chunkLocations(firstWorldId, 0L).isEmpty());
        assertEquals(1, index.removeWorld(firstWorldId).size());
        assertTrue(index.worldLocations(firstWorldId).isEmpty());
        assertTrue(index.chunkLocations(firstWorldId, 1L << 32).isEmpty());
    }

    @Test
    void ignoresLocationsWithoutWorldAndClearsAllIndexes() {
        WorldChunkLocationIndex index = new WorldChunkLocationIndex();
        UUID worldId = UUID.randomUUID();
        World world = world(worldId);
        Location location = new Location(world, 1, 64, 1);

        index.add(new Location(null, 1, 64, 1));
        index.add(location);
        index.clear();

        assertTrue(index.worldLocations(worldId).isEmpty());
        assertTrue(index.chunkLocations(worldId, 0L).isEmpty());
    }

    private static World world(UUID id) {
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUID" -> id;
                    case "getName" -> "test-world";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "test-world";
                    default -> throw new UnsupportedOperationException(method.getName());
                }
        );
    }
}
