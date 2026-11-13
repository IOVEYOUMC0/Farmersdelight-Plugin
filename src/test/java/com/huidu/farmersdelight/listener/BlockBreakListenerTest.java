package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.api.FarmersDelightApi;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockBreakListenerTest {

    @Test
    void protectsFarmersDelightAndRegisteredAddonBlocksOnly() {
        FarmersDelightApi.get().registerAddonBlockNamespace("testaddon");

        assertTrue(BlockBreakListener.isProtectedBlockId("farmersdelight:rice"));
        assertTrue(BlockBreakListener.isProtectedBlockId("testaddon:crop"));
        assertFalse(BlockBreakListener.isProtectedBlockId("minecraft:wheat"));
        assertFalse(BlockBreakListener.isProtectedBlockId("invalid"));
    }

    @Test
    void crossRegionBreaksCaptureTheBreakerStateOnThePlayerScheduler() throws IOException {
        String source = read("src/main/java/com/huidu/farmersdelight/listener/BlockBreakListener.java");
        int deferral = source.indexOf("handleCrossRegionBreak(event, state, station, breaker)");
        assertTrue(deferral > 0, "a break that does not own the breaker must take the deferred path");

        int hop = source.indexOf("plugin.scheduler().runForEntity(breaker", deferral);
        assertTrue(hop > deferral, "the breaker snapshot must be captured on the player's scheduler");
        int tool = source.indexOf("ItemStack tool = breaker.getInventory().getItemInMainHand();", hop);
        assertTrue(tool > hop, "the tool snapshot must be read inside the player-scheduler task");
        int gameMode = source.indexOf("boolean creative = breaker.getGameMode() == GameMode.CREATIVE;", hop);
        assertTrue(gameMode > hop, "the creative decision must use the snapshot captured there");

        int settlement = source.indexOf("RegionTasks.runAtLoadedChunk(plugin.scheduler(), world", hop);
        assertTrue(settlement > hop, "the deferred settlement must return to the block's chunk");
        assertTrue(source.indexOf("callEvent(new FarmersDelightStationBrokenEvent(", settlement) > settlement,
                "the station event must be fired from the block's region");
        assertTrue(source.indexOf("creativeGatedDrops.flush(", settlement) > settlement,
                "the captured drops must be settled from the block's region");
    }

    @Test
    void theOwnedPathKeepsTheSynchronousOrderAndTheCreativeGate() throws IOException {
        String source = read("src/main/java/com/huidu/farmersdelight/listener/BlockBreakListener.java");
        int ownedCheck = source.indexOf("!plugin.scheduler().isOwnedByCurrentRegion(breaker)");
        assertTrue(ownedCheck > 0);

        int event = source.indexOf("callEvent(new FarmersDelightStationBrokenEvent(", ownedCheck);
        int cleanup = source.indexOf("cleanupManagedBreak(event, state, creative)", ownedCheck);
        assertTrue(event > ownedCheck, "the owned path still fires the station event");
        assertTrue(cleanup > event, "the owned path still fires the station event before the cleanup");
        assertTrue(source.contains("boolean creative = breaker != null && breaker.getGameMode() == GameMode.CREATIVE;"),
                "the game mode is captured once and reused");
        assertTrue(source.contains("platformDrops && !creative"));
    }

    @Test
    void theDeferredSettlementRechecksThePluginAndSkipsDropsForCreative() throws IOException {
        String source = read("src/main/java/com/huidu/farmersdelight/listener/BlockBreakListener.java");
        int settlement = source.indexOf("private void settleBrokenStationLater(");
        assertTrue(settlement > 0);
        int flush = source.indexOf("if (!creative && creativeGatedDrops != null)", settlement);
        int enabledCheck = source.indexOf("if (!plugin.isEnabled()", settlement);
        assertTrue(enabledCheck > settlement, "the deferred settlement must re-check the plugin is still enabled");
        assertTrue(flush > settlement, "creative drops must be discarded, not settled");
        assertTrue(source.contains("void flush(World world, Location dropLocation)"));
    }

    private static String read(String relative) throws IOException {
        for (String prefix : new String[]{"", "FarmersDelight/"}) {
            Path path = Path.of(prefix + relative);
            if (Files.isRegularFile(path)) return Files.readString(path);
        }
        throw new IOException("source not found: " + relative);
    }
}
