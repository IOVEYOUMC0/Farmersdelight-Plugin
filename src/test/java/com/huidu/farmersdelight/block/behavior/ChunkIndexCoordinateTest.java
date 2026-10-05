package com.huidu.farmersdelight.block.behavior;

import com.huidu.farmersdelight.util.BlockPosKey;
import com.huidu.farmersdelight.util.ManagerSupport;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ManagerSupport#chunkKey(int, int) takes CHUNK coordinates, and the index behind the cooking-pot and
 * cutting-board registries is read back with chunk coordinates as well
 * (getBlockEntitiesInChunk(world, chunkX, chunkZ)). A block position handed to that overload therefore
 * creates its own bogus bucket - block x=50 lands under key chunk 50 instead of chunk 3 - so the entry is
 * invisible to the chunk lookup: a chunk unload never finds it, and the bucket for the real chunk stays empty.
 *
 * These tests drive the production write paths (the cutting board's public putBlockEntity/
 * removeBlockEntity and the pot's private indexAdd/indexRemove) and read the index back through
 * the public chunk lookup, with a proxy World supplying getUID() only - no server, no live chunk.
 */
class ChunkIndexCoordinateTest {

    private static final Field POT_WORLD_ENTITIES = field(CookingPotBlockBehavior.class, "worldBlockEntities");
    private static final Field POT_CHUNK_INDEX = field(CookingPotBlockBehavior.class, "chunkIndex");
    private static final Method POT_INDEX_ADD = method(CookingPotBlockBehavior.class, "indexAdd");
    private static final Method POT_INDEX_REMOVE = method(CookingPotBlockBehavior.class, "indexRemove");

    // A pot at (50, 64, -27): chunk (3, -2). The block-coordinate form keys it as chunk (50, -27).
    private static final BlockPosKey POT_IN_CHUNK_3_NEGATIVE_2 = new BlockPosKey(50, 64, -27);

    @Test
    void cuttingBoardPutIsVisibleToItsOwnChunk() {
        UUID worldId = UUID.randomUUID();
        World world = stubWorld(worldId);
        BlockPosKey key = POT_IN_CHUNK_3_NEGATIVE_2;
        try {
            CuttingBoardBlockBehavior.putBlockEntity(world, key,
                    new CuttingBoardBlockEntity(null, key, world));

            assertEquals(Set.of(key), CuttingBoardBlockBehavior.getBlockEntitiesInChunk(world, 3, -2).keySet(),
                    "A board at x=50,z=-27 must be found under chunk (3,-2); the write path must not key it by block coords");
            assertTrue(CuttingBoardBlockBehavior.getBlockEntitiesInChunk(world, 50, -27).isEmpty(),
                    "chunk (50,-27) is the bogus bucket a block-coordinate key would create; nothing belongs there");
        } finally {
            CuttingBoardBlockBehavior.cleanupWorld(worldId, false);
        }
    }

    @Test
    void cuttingBoardChunkBorderSplitsNeighbours() {
        UUID worldId = UUID.randomUUID();
        World world = stubWorld(worldId);
        // x=15 is the last block of chunk 0, x=16 the first of chunk 1; same for z.
        BlockPosKey borderX = new BlockPosKey(15, 64, 16);
        BlockPosKey borderZ = new BlockPosKey(16, 64, 15);
        try {
            CuttingBoardBlockBehavior.putBlockEntity(world, borderX, new CuttingBoardBlockEntity(null, borderX, world));
            CuttingBoardBlockBehavior.putBlockEntity(world, borderZ, new CuttingBoardBlockEntity(null, borderZ, world));

            assertEquals(Set.of(borderX), CuttingBoardBlockBehavior.getBlockEntitiesInChunk(world, 0, 1).keySet());
            assertEquals(Set.of(borderZ), CuttingBoardBlockBehavior.getBlockEntitiesInChunk(world, 1, 0).keySet());
            assertTrue(CuttingBoardBlockBehavior.getBlockEntitiesInChunk(world, 0, 0).isEmpty(),
                    "Blocks 15 and 16 must not share a chunk");
            assertTrue(CuttingBoardBlockBehavior.getBlockEntitiesInChunk(world, 1, 1).isEmpty(),
                    "Blocks 15 and 16 must not share a chunk");
        } finally {
            CuttingBoardBlockBehavior.cleanupWorld(worldId, false);
        }
    }

    @Test
    void cuttingBoardRemoveDropsTheEntryFromItsChunk() {
        UUID worldId = UUID.randomUUID();
        World world = stubWorld(worldId);
        BlockPosKey removed = POT_IN_CHUNK_3_NEGATIVE_2;
        BlockPosKey kept = new BlockPosKey(49, 64, -26);  // Same chunk (3,-2).
        try {
            CuttingBoardBlockBehavior.putBlockEntity(world, removed, new CuttingBoardBlockEntity(null, removed, world));
            CuttingBoardBlockBehavior.putBlockEntity(world, kept, new CuttingBoardBlockEntity(null, kept, world));

            CuttingBoardBlockBehavior.removeBlockEntity(world, removed, false);

            assertEquals(Set.of(kept), CuttingBoardBlockBehavior.getBlockEntitiesInChunk(world, 3, -2).keySet(),
                    "Removal must clear the same bucket the add wrote, and only the removed position");
        } finally {
            CuttingBoardBlockBehavior.cleanupWorld(worldId, false);
        }
    }

    @Test
    void cookingPotIndexAddIsVisibleToItsOwnChunk() throws Exception {
        UUID worldId = UUID.randomUUID();
        World world = stubWorld(worldId);
        BlockPosKey key = POT_IN_CHUNK_3_NEGATIVE_2;
        try {
            potEntities(worldId).put(key, new CookingPotBlockEntity(key));
            POT_INDEX_ADD.invoke(null, worldId, key);

            assertEquals(Set.of(key), CookingPotBlockBehavior.getBlockEntitiesInChunk(world, 3, -2).keySet(),
                    "A pot at x=50,z=-27 must be found under chunk (3,-2); indexAdd must not key it by block coords");
            assertTrue(CookingPotBlockBehavior.getBlockEntitiesInChunk(world, 50, -27).isEmpty(),
                    "chunk (50,-27) is the bogus bucket a block-coordinate key would create; nothing belongs there");
        } finally {
            cleanupPot(worldId);
        }
    }

    @Test
    void cookingPotNegativeBlockLandsInTheNegativeChunk() throws Exception {
        UUID worldId = UUID.randomUUID();
        World world = stubWorld(worldId);
        // x=-1 and z=-1 are inside chunk (-1,-1): arithmetic shift, not /16 (which would yield chunk 0).
        BlockPosKey key = new BlockPosKey(-1, 64, -1);
        try {
            potEntities(worldId).put(key, new CookingPotBlockEntity(key));
            POT_INDEX_ADD.invoke(null, worldId, key);

            assertEquals(Set.of(key), CookingPotBlockBehavior.getBlockEntitiesInChunk(world, -1, -1).keySet(),
                    "Block -1 belongs to chunk -1; a /16 would put it in chunk 0");
            assertTrue(CookingPotBlockBehavior.getBlockEntitiesInChunk(world, 0, 0).isEmpty(),
                    "Chunk 0 is where a naive division would file block -1; the shift must not do that");
        } finally {
            cleanupPot(worldId);
        }
    }

    @Test
    void cookingPotIndexRemoveDropsTheEntryFromItsChunk() throws Exception {
        UUID worldId = UUID.randomUUID();
        World world = stubWorld(worldId);
        BlockPosKey removed = POT_IN_CHUNK_3_NEGATIVE_2;
        BlockPosKey kept = new BlockPosKey(49, 64, -26);  // Same chunk (3,-2).
        try {
            potEntities(worldId).put(removed, new CookingPotBlockEntity(removed));
            potEntities(worldId).put(kept, new CookingPotBlockEntity(kept));
            POT_INDEX_ADD.invoke(null, worldId, removed);
            POT_INDEX_ADD.invoke(null, worldId, kept);

            POT_INDEX_REMOVE.invoke(null, worldId, removed);

            assertEquals(Set.of(kept), CookingPotBlockBehavior.getBlockEntitiesInChunk(world, 3, -2).keySet(),
                    "Removal must clear the same bucket the add wrote, and only the removed position");
        } finally {
            cleanupPot(worldId);
        }
    }

    @Test
    void chunkKeyShiftsBlockPositionsTowardsNegativeInfinity() {
        UUID worldId = UUID.randomUUID();
        World world = stubWorld(worldId);

        // x=15 and x=16 must land in different chunks; x=-1 in chunk -1 (while -1/16 == 0); x=-16 in chunk -1
        // (the last block of it); x=-17 in chunk -2.
        assertChunk(world, 15, 7, 0, 0);
        assertChunk(world, 16, 7, 1, 0);
        assertChunk(world, -1, -1, -1, -1);
        assertChunk(world, -16, -17, -1, -2);
        assertChunk(world, 7, 15, 0, 0);
        assertChunk(world, 7, 16, 0, 1);

        assertNotEquals(ManagerSupport.chunkKey(new Location(world, 15, 0, 0)),
                ManagerSupport.chunkKey(new Location(world, 16, 0, 0)),
                "x=15 and x=16 are in different chunks");
        assertNotEquals(ManagerSupport.chunkKey(new Location(world, -1, 0, 0)),
                ManagerSupport.chunkKey(new Location(world, 0, 0, 0)),
                "x=-1 and x=0 are in different chunks");
    }

    private static void assertChunk(World world, int blockX, int blockZ, int chunkX, int chunkZ) {
        assertEquals(ManagerSupport.chunkKey(chunkX, chunkZ),
                ManagerSupport.chunkKey(new Location(world, blockX, 64, blockZ)),
                "block (" + blockX + "," + blockZ + ") must hash to chunk (" + chunkX + "," + chunkZ + ")");
    }

    /** The cutting board and pot indexes both use the same block-coordinate bug; this pins the shared helper. */
    private static World stubWorld(UUID worldId) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUID" -> worldId;
                    case "getName" -> "chunk-index-test";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "stub-world";
                    default -> defaultValue(method);
                });
    }

    private static Object defaultValue(Method method) {
        Class<?> type = method.getReturnType();
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) return false;
        if (type == void.class) return null;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0D;
        if (type == float.class) return 0.0F;
        if (type == char.class) return (char) 0;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        return 0;
    }

    @SuppressWarnings("unchecked")
    private static Map<BlockPosKey, CookingPotBlockEntity> potEntities(UUID worldId) throws Exception {
        Map<UUID, Map<BlockPosKey, CookingPotBlockEntity>> all =
                (Map<UUID, Map<BlockPosKey, CookingPotBlockEntity>>) POT_WORLD_ENTITIES.get(null);
        return all.computeIfAbsent(worldId, key -> new ConcurrentHashMap<>());
    }

    @SuppressWarnings("unchecked")
    private static void cleanupPot(UUID worldId) throws Exception {
        ((Map<UUID, ?>) POT_WORLD_ENTITIES.get(null)).remove(worldId);
        ((Map<UUID, ?>) POT_CHUNK_INDEX.get(null)).remove(worldId);
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Method method(Class<?> owner, String name) {
        try {
            Method method = owner.getDeclaredMethod(name, UUID.class, BlockPosKey.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
