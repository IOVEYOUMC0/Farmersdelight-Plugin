package com.huidu.farmersdelight.listener;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The rope path must never cancel an interaction it does not own — the live defect behind "the bell at the top
 * of the rope cannot be rung" was assumed to be exactly such a blanket cancel.
 *
 *
 * Asserted on the source rather than through reflection: what has to hold is that no cancellation exists outside
 * the one snaking-empty-hand retract path, and only the text shows that. Cross-checked here together with the
 * bell scan: a click that lands on a bell, a door or a button never enters the plugin's rope code at all, because the
 * rope code has no PlayerInteractEvent handler and its only CE-scoped cancel requires the clicked block
 * to carry the rope behavior.
 */
class RopeListenerSourceTest {

    @Test
    void theRopeListenerOnlyCancelsTheSneakRetract() throws IOException {
        String source = read("listener/RopeBlockListener.java");
        assertEquals(1, source.split("setCancelled\\(", -1).length - 1,
                "exactly one cancel: the sneaking empty-hand retract");
        assertFalse(source.contains("setUseItemInHand"),
                "the rope path must not deny the item use of a click it does not own");
        assertFalse(source.contains("setUseInteractedBlock"),
                "nor the interacted block, or a bell/door/button beside a rope would stop working");
        assertFalse(source.contains("PlayerInteractEvent"),
                "no PlayerInteractEvent handler at all: vanilla interactions never reach us");
    }

    @Test
    void theRopeBehaviorOnlyCancelsWhenItPlacesARope() throws IOException {
        String source = read("block/behavior/RopeBlockBehavior.java");
        assertFalse(source.contains("setCancelled"),
                "the block behavior reports InteractionResult instead of cancelling events");
        assertTrue(source.contains("RopeBellScan.findBellOffset("),
                "the bell scan is shared by both empty-hand entry points");
    }

    private static void assertTrue(boolean value, String message) {
        org.junit.jupiter.api.Assertions.assertTrue(value, message);
    }

    /** Finds the source from the test working directory the same way the other source assertions do. */
    private static String read(String relative) throws IOException {
        Path cursor = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 4 && cursor != null; depth++) {
            for (String prefix : new String[]{"FarmersDelight/src/main/java/com/huidu/farmersdelight/",
                    "src/main/java/com/huidu/farmersdelight/"}) {
                Path candidate = cursor.resolve(prefix + relative);
                if (Files.isRegularFile(candidate)) {
                    return Files.readString(candidate);
                }
            }
            cursor = cursor.getParent();
        }
        assertNotNull(null, "the source file has to be reachable from the test working directory: " + relative);
        return "";
    }
}
