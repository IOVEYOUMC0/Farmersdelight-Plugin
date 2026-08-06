package com.huidu.farmersdelight.visual;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyItemDisplayManagerTest {

    @Test
    void candidateRadiusCoversConfiguredViewDistance() {
        assertEquals(1, ProxyItemDisplayManager.candidateChunkRadius(0.0D));
        assertEquals(1, ProxyItemDisplayManager.candidateChunkRadius(16.0D));
        assertEquals(2, ProxyItemDisplayManager.candidateChunkRadius(16.01D));
        assertEquals(4, ProxyItemDisplayManager.candidateChunkRadius(64.0D));
        assertEquals(5, ProxyItemDisplayManager.candidateChunkRadius(64.01D));
    }

    @Test
    void chunkComparisonIncludesWorldAndChunkCoordinates() {
        World firstWorld = world(UUID.randomUUID());
        World secondWorld = world(UUID.randomUUID());

        assertTrue(ProxyItemDisplayManager.sameDisplayChunk(
                new Location(firstWorld, 1, 64, 1), new Location(firstWorld, 15, 80, 15)));
        assertFalse(ProxyItemDisplayManager.sameDisplayChunk(
                new Location(firstWorld, 15, 64, 15), new Location(firstWorld, 16, 64, 15)));
        assertFalse(ProxyItemDisplayManager.sameDisplayChunk(
                new Location(firstWorld, 1, 64, 1), new Location(secondWorld, 1, 64, 1)));
    }

    private static World world(UUID id) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUID" -> id;
                    case "getName", "toString" -> "test-" + id;
                    case "hashCode" -> id.hashCode();
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0F;
        if (type == double.class) return 0.0D;
        if (type == char.class) return '\0';
        return null;
    }
}
