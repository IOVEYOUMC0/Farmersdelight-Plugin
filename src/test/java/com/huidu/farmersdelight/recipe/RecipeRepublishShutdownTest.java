package com.huidu.farmersdelight.recipe;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An addon unregisters its recipes while it disables, which schedules a one-tick republish. On a normal stop
 * CraftEngine is disabled first, so that deferred pass would run against an empty item registry and report every
 * result item and tag ingredient as invalid. The republish must check the shutdown state before loading.
 */
class RecipeRepublishShutdownTest {

    private static final Path[] MANAGERS = {
            Path.of("src", "main", "java", "com", "huidu", "farmersdelight", "recipe",
                    "CookingPotRecipeManager.java"),
            Path.of("src", "main", "java", "com", "huidu", "farmersdelight", "recipe",
                    "CuttingBoardRecipeManager.java"),
    };

    @Test
    void theDeferredRepublishSkipsShutdown() throws IOException {
        for (Path manager : MANAGERS) {
            String flat = Files.readString(manager).replaceAll("\\s+", " ");
            int schedule = flat.indexOf("private void scheduleExternalRepublish()");
            assertTrue(schedule >= 0, manager.getFileName() + " must keep its deferred republish");
            String body = flat.substring(schedule, Math.min(flat.length(), schedule + 1200));
            int guard = body.indexOf("ShutdownGate.isClosing(plugin)");
            int load = body.indexOf("loadRecipes()");
            assertTrue(guard >= 0,
                    manager.getFileName() + " must skip the republish during shutdown");
            assertTrue(load > guard,
                    manager.getFileName() + " must check shutdown before it loads recipes");
        }
    }

    @Test
    void theCraftEngineReloadHandlerSkipsShutdown() throws IOException {
        String flat = Files.readString(Path.of("src", "main", "java", "com", "huidu", "farmersdelight",
                "FarmersDelightPlugin.java")).replaceAll("\\s+", " ");
        int handler = flat.indexOf("public void onCraftEngineReload(CraftEngineReloadEvent event)");
        assertTrue(handler >= 0, "the handler must exist");
        String body = flat.substring(handler, Math.min(flat.length(), handler + 1500));
        int guard = body.indexOf("ShutdownGate.isClosing(this)");
        int work = body.indexOf("queueReloadProcessing()");
        assertTrue(guard >= 0, "a CraftEngine disable also fires the reload event");
        assertTrue(work > guard, "the shutdown check must come before the queued reload work");
    }
}
