package com.huidu.farmersdelight.listener;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Explosion cleanup must run at MONITOR after other plugins had a chance to cancel the event; running it at
 * HIGHEST drops the contents before a later cancellation can roll the block back.
 */
class ExplosionCleanupPriorityTest {

    @Test
    void explosionCleanupRunsAtMonitor() throws IOException {
        String source = read("src/main/java/com/huidu/farmersdelight/listener/BlockBreakListener.java");
        String monitor = "@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)";
        assertHandlerUses(source, "public void onBlockExplode(BlockExplodeEvent event)", monitor);
        assertHandlerUses(source, "public void onEntityExplode(EntityExplodeEvent event)", monitor);
    }

    private static void assertHandlerUses(String source, String signature, String annotation) {
        int at = source.indexOf(signature);
        assertTrue(at >= 0, "missing handler: " + signature);
        int from = Math.max(0, at - 200);
        assertTrue(source.substring(from, at).contains(annotation),
                signature + " must use " + annotation);
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
