package com.huidu.farmersdelight.api.block;

import com.huidu.farmersdelight.api.content.ContentId;
import com.huidu.farmersdelight.api.event.FarmersDelightHarvestEvent;
import com.huidu.farmersdelight.api.event.FarmersDelightMealConsumedEvent;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline contract tests for the live-crop API; no server or CraftEngine content is required. */
class CropApiContractTest {

    @Test
    void cropQueriesFailClosedOnNull() {
        assertFalse(FarmersDelightCrops.isMature(null));
        assertNull(FarmersDelightCrops.snapshot(null));
        assertFalse(FarmersDelightCrops.isCropBlockId((ContentId) null));
    }

    @Test
    void liveBlockQueriesDoNotLoadAnUnloadedChunk() {
        World world = (World) Proxy.newProxyInstance(
                World.class.getClassLoader(), new Class<?>[]{World.class}, (proxy, method, args) ->
                        "isChunkLoaded".equals(method.getName()) ? Boolean.FALSE : null);
        Block block = (Block) Proxy.newProxyInstance(
                Block.class.getClassLoader(), new Class<?>[]{Block.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getWorld" -> world;
                    case "getX", "getZ" -> 0;
                    default -> null;
                });

        assertFalse(FarmersDelightCrops.isCrop(block), "an unloaded chunk is not a crop");
        assertNull(FarmersDelightCrops.snapshot(block));
        assertFalse(FarmersDelightCrops.isHarvestReady(block));
        assertEquals(-1, FarmersDelightCrops.tallCropAge(block));
        assertFalse(FarmersDelightCrops.resetsOnHarvest(block));
    }

    @Test
    void snapshotCarriesStablePlainValues() {
        CropSnapshot snapshot = new CropSnapshot("testaddon:crop", 2, 3, "testaddon:seed",
                false, false, true);
        assertEquals("testaddon:crop", snapshot.blockId());
        assertEquals(2, snapshot.age());
        assertEquals(3, snapshot.maxAge());
        assertEquals("testaddon:seed", snapshot.seedItemId());
        assertTrue(snapshot.mature());
    }

    @Test
    void actorEventsHaveFactoryThatKeepsNullUnambiguous() {
        FarmersDelightHarvestEvent harvest = FarmersDelightHarvestEvent.forActor(
                null, null, "testaddon:crop", null, List.of());
        assertNull(harvest.getActor());
        assertNull(harvest.getActorId());
        assertNull(harvest.getPlayerId());

        FarmersDelightMealConsumedEvent meal = FarmersDelightMealConsumedEvent.forActor(
                null, "testaddon:meal", null);
        assertNull(meal.getActor());
        assertNull(meal.getActorId());
        assertNull(meal.getPlayerId());
    }
}
