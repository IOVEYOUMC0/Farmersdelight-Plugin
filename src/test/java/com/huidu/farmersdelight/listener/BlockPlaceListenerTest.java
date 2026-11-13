package com.huidu.farmersdelight.listener;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-contract checks: the placement handlers cannot be driven offline because CraftEngine hands them its
 * own event payload. They lock the thread shape — the placed item snapshot comes from the events themselves, and
 * a foreign player region defers the block work instead of reading the player from the block's region thread.
 */
class BlockPlaceListenerTest {

    @Test
    void thePlacedItemSnapshotComesFromTheBlockPlaceEvent() throws IOException {
        String source = read("src/main/java/com/huidu/farmersdelight/listener/BlockPlaceListener.java");
        assertTrue(source.contains("cachePlacedItem(player, event.getHand(), customBlockId, event.getItemInHand())"),
                "the placed item must be snapshotted from the vanilla placement event");
        assertTrue(source.contains("consumePendingPlacedItem(player, event.hand(), customBlockId)"),
                "the custom place event consumes that snapshot instead of the live inventory");
        assertFalse(source.contains("event.player().getInventory()"),
                "the custom place event must not read the player's inventory");
    }

    @Test
    void aForeignPlayerRegionDefersTheBlockWorkToTheOwnersThreads() throws IOException {
        String source = read("src/main/java/com/huidu/farmersdelight/listener/BlockPlaceListener.java");
        assertTrue(source.contains("!plugin.scheduler().isOwnedByCurrentRegion(player)"));

        int deferral = source.indexOf("plugin.scheduler().runForEntity(player", 0);
        assertTrue(deferral > 0, "the held-item capture must run on the player's scheduler");
        int capture = source.indexOf("ItemStack held = heldItem(player, hand);", deferral);
        assertTrue(capture > deferral);
        int continuation = source.indexOf("RegionTasks.runAtLoadedChunk(plugin.scheduler(), blockLocation.getWorld()", capture);
        assertTrue(continuation > capture, "the block work must continue on the block's chunk");
        assertTrue(source.indexOf("awardForCustomBlock(player, customBlockId, blockLocation, held)", continuation) > continuation);
        assertTrue(source.indexOf("if (!plugin.isEnabled()", continuation) > continuation);
    }

    @Test
    void aForeignAttemptPlaceSkipsTheInventorySnapshot() throws IOException {
        String source = read("src/main/java/com/huidu/farmersdelight/listener/BlockPlaceListener.java");
        int attempt = source.indexOf("public void onCustomBlockAttemptPlace(CustomBlockAttemptPlaceEvent event)");
        assertTrue(attempt > 0);
        int foreign = source.indexOf("!plugin.scheduler().isOwnedByCurrentRegion(player)", attempt);
        assertTrue(foreign > attempt, "the attempt handler must check who owns the player");
        assertTrue(source.indexOf("cacheAttemptedPlaceItem(player, event.hand(), customBlockId)", foreign) > foreign,
                "the inventory read must sit on the owned branch only");
    }

    private static String read(String relative) throws IOException {
        for (String prefix : new String[]{"", "FarmersDelight/"}) {
            Path path = Path.of(prefix + relative);
            if (Files.isRegularFile(path)) return Files.readString(path);
        }
        throw new IOException("source not found: " + relative);
    }
}
