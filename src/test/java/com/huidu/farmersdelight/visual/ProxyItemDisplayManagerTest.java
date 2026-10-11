package com.huidu.farmersdelight.visual;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyItemDisplayManagerTest {

    @Test
    void cullingBoundsIncludeTranslationRotationAndNegativeScale() {
        Transformation transformation = new Transformation(new Vector3f(2, 3, 4),
                new Quaternionf().rotateY(0.8F), new Vector3f(-2, 1, 3), new Quaternionf().rotateX(0.4F));
        var data = ProxyItemDisplayManager.cullingData(new Location(null, 10, 20, 30),
                transformation, 0.5D, 48, true);
        assertEquals(48, data.maxDistance);
        assertTrue(data.rayTracing);
        for (int x = -1; x <= 1; x += 2) {
            for (int y = -1; y <= 1; y += 2) {
                for (int z = -1; z <= 1; z += 2) {
                    Vector3f corner = new Vector3f(x * 0.5F, y * 0.5F, z * 0.5F);
                    transformation.getRightRotation().transform(corner);
                    corner.mul(transformation.getScale());
                    transformation.getLeftRotation().transform(corner);
                    corner.add(12, 23, 34);
                    assertTrue(corner.x >= data.aabb.minX && corner.x <= data.aabb.maxX);
                    assertTrue(corner.y >= data.aabb.minY && corner.y <= data.aabb.maxY);
                    assertTrue(corner.z >= data.aabb.minZ && corner.z <= data.aabb.maxZ);
                }
            }
        }
    }

    @Test
    void seeThroughDisplayCanKeepNativeDistanceCullingWithoutRayTracing() {
        var data = ProxyItemDisplayManager.cullingData(new Location(null, 0, 0, 0),
                new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(1), new Quaternionf()),
                1.0D, -1, false);
        assertFalse(data.rayTracing);
        assertEquals(-1, data.maxDistance);
        assertTrue(Double.isFinite(data.aabb.minX));
    }

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
