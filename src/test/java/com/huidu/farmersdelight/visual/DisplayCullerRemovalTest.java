package com.huidu.farmersdelight.visual;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-viewer culler for real display entities is gone: nothing spawns a real display any more, so it had
 * no consumer and its tests guarded a pass that never ran. What stays is the rule the proxy display path
 * shares, and the two periodic slots that still have a consumer.
 */
class DisplayCullerRemovalTest {

    private static final Path MAIN = Path.of("src", "main", "java", "com", "huidu", "farmersdelight");
    private static final List<String> REMOVED_TYPES = List.of("RealDisplayCuller", "DisplayViewMirror",
            "DisplayViewSettings");

    @Test
    void nothingReferencesTheRemovedCuller() throws IOException {
        for (Path path : Files.walk(MAIN).filter(file -> file.toString().endsWith(".java")).toList()) {
            String text = Files.readString(path);
            for (String type : REMOVED_TYPES) {
                assertFalse(text.contains(type), MAIN.relativize(path) + " still references " + type);
            }
        }
    }

    @Test
    void theSharedRuleAndTheProxyDisplayPathStay() throws IOException {
        assertTrue(Files.isRegularFile(MAIN.resolve("visual/DisplayCulling.java")),
                "the view-range rule the proxy display path uses has to stay");
        String proxy = Files.readString(MAIN.resolve("visual/ProxyItemDisplayManager.java"));
        assertTrue(proxy.contains("DisplayCulling.isVisible("),
                "the production display path still culls its viewers with the shared rule");
    }

    @Test
    void onlyTheSlotsWithAConsumerStayAndTheRemovedOneIsRejected() {
        assertEquals(List.of("tick-cleanup", "display-sync"),
                com.huidu.farmersdelight.util.PeriodicStagger.slots(), "the slot list follows its consumers");
        assertThrows(IllegalArgumentException.class,
                () -> com.huidu.farmersdelight.util.PeriodicStagger.initialDelay("display-cull", 20L),
                "the removed culling slot must not be usable again");
    }

    @Test
    void theConfigNoLongerCarriesTheKeysTheCullerRead() throws IOException {
        String config = Files.readString(Path.of("src", "main", "resources", "config.yml"));
        assertFalse(config.contains("display-cull-checks-per-player"),
                "the budget key had no reader left once the culler went");
        assertFalse(config.contains("display-culling:"),
                "the per-type culling key had no reader left once the culler went");
    }
}
