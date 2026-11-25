package com.huidu.farmersdelight.manager;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Skillet/Stove effects run on the block's region thread, so they must take the tracked chunk viewers and recheck
 * distance on each viewer's own scheduler. Reading player coordinates while collecting viewers is a cross-region
 * entity read on Folia.
 */
class ViewerEffectDispatchContractTest {

    @Test
    void skilletUsesTrackedChunkViewersAndPerViewerRange() throws IOException {
        String source = read("src/main/java/com/huidu/farmersdelight/manager/SkilletEffectManager.java");
        assertFalse(source.contains("ManagerSupport.collectNearbyPlayers("),
                "the skillet must not filter viewers by reading player coordinates on the block thread");
        assertTrue(source.contains("ManagerSupport.playersSeeingChunk("),
                "the skillet must collect viewers from chunk tracking");
        assertTrue(source.contains("ManagerSupport.runForViewerWithin("),
                "the skillet must recheck distance on each viewer's scheduler");
    }

    @Test
    void stoveUsesTrackedChunkViewersAndPerViewerRange() throws IOException {
        String source = read("src/main/java/com/huidu/farmersdelight/manager/StoveManager.java");
        int tick = source.indexOf("private void tickStove(");
        assertTrue(tick >= 0, "tickStoveEffects must exist");
        int end = source.indexOf("private boolean isStoveLit", tick);
        String body = source.substring(tick, end > tick ? end : source.length());
        assertFalse(body.contains("p.getX()"), "the stove must not read viewer coordinates while collecting");
        assertTrue(body.contains("ManagerSupport.runForViewerWithin("),
                "the stove must recheck distance on each viewer's scheduler");
    }

    private static String read(String relative) throws IOException {
        for (String prefix : new String[]{"", "FarmersDelight/"}) {
            Path path = Path.of(prefix, relative);
            if (Files.isRegularFile(path)) {
                return Files.readString(path);
            }
        }
        throw new IOException("source not found: " + relative);
    }
}
