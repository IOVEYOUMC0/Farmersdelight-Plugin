package com.huidu.farmersdelight.gui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the startup warmup that keeps the first GUI/recipe-book open from paying the lazy-config cost. */
class GuiWarmupContractTest {

    @Test
    void warmupResolvesCookingPotAndRecipeGuis() throws IOException {
        String coordinator = read("src/main/java/com/huidu/farmersdelight/CraftEngineReadinessCoordinator.java");
        for (String call : new String[]{
                "plugin.warmCookingPotGuiConfigs()",
                "RecipeBookGui.warmUp(plugin)",
                "RecipeViewGui.warmUp(plugin)"}) {
            assertTrue(coordinator.contains(call), "warmup must call " + call);
        }
        String book = read("src/main/java/com/huidu/farmersdelight/gui/recipebook/RecipeBookGui.java");
        assertTrue(book.contains("config(plugin).warmItems()"), "recipe book config must be parsed at warmup");
        assertTrue(book.contains("progressFrameItem()"), "the animated progress item must be resolved at warmup");
        String view = read("src/main/java/com/huidu/farmersdelight/gui/RecipeViewGui.java");
        assertTrue(view.contains("loadConfig(plugin)).warmItems()"), "recipe view config/items must be resolved");
        String pot = read("src/main/java/com/huidu/farmersdelight/gui/GuiConfig.java");
        assertTrue(pot.contains("public void warmItems()"), "GuiConfig needs the shared item warmer");
    }

    private static String read(String relative) throws IOException {
        for (String prefix : new String[]{"", "FarmersDelight/"}) {
            Path path = Path.of(prefix + relative);
            if (Files.isRegularFile(path)) return Files.readString(path);
        }
        throw new IOException("source not found: " + relative);
    }
}
