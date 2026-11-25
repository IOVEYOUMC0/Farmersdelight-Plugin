package com.huidu.farmersdelight.api.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A deferred task replays after the state it was queued for is gone, so each replay point has to ask the shared
 * gate instead of testing the two conditions by hand. On a normal stop CraftEngine disables first, and a pass that
 * runs anyway reads empty registries and reports every entry as invalid.
 */
class ShutdownGateTest {

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");

    @Test
    void theGatedFilesAskTheSharedHelperInsteadOfTestingBothConditionsByHand() throws IOException {
        List<String> handRolled = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (path.getFileName().toString().equals("ShutdownGate.java")) {
                    continue;
                }
                String flat = Files.readString(path).replaceAll("\\s+", " ");
                if (flat.contains("getServer().isStopping() || !") || flat.contains("isStopping() || !addon.isEnabled()")) {
                    handRolled.add(path.getFileName().toString());
                }
            }
        }
        assertTrue(handRolled.isEmpty(),
                "these files test the shutdown conditions by hand instead of using ShutdownGate: " + handRolled);
    }

    @Test
    void theGateCoversTheServerStopAndThePluginDisable() throws IOException {
        String flat = Files.readString(SOURCE_ROOT.resolve(Path.of("com", "huidu", "farmersdelight",
                "api", "util", "ShutdownGate.java"))).replaceAll("\\s+", " ");
        assertTrue(flat.contains("!plugin.isEnabled()"), "a disabled plugin must count as closing");
        assertTrue(flat.contains("plugin.getServer().isStopping()"), "a stopping server must count as closing");
        assertTrue(flat.contains("plugin == null"), "a missing plugin reference must count as closing");
    }

    @Test
    void theDeferredRepublishAndResyncAreGated() throws IOException {
        String[] gated = {
                "com/huidu/farmersdelight/recipe/CookingPotRecipeManager.java",
                "com/huidu/farmersdelight/recipe/CuttingBoardRecipeManager.java",
                "com/huidu/farmersdelight/advancement/AdvancementSystem.java",
                "com/huidu/farmersdelight/listener/CraftEngineWatchdogListener.java",
                "com/huidu/farmersdelight/api/util/RequiredPluginWatchdog.java",
        };
        for (String relative : gated) {
            String flat = Files.readString(SOURCE_ROOT.resolve(relative)).replaceAll("\\s+", " ");
            assertTrue(flat.contains("ShutdownGate.isClosing("),
                    relative + " replays deferred work and must ask the gate");
        }
    }
}
