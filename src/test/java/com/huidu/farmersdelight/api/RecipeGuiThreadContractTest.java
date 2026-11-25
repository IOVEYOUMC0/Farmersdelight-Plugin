package com.huidu.farmersdelight.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The public recipe-book/editor entry points can be called by addons from a non-player region on Folia. They must
 * route the actual openInventory through the player's entity scheduler, not run on the caller thread.
 */
class RecipeGuiThreadContractTest {

    @Test
    void publicRecipeGuiEntryPointsDispatchToThePlayer() throws IOException {
        String source = read("src/main/java/com/huidu/farmersdelight/api/FarmersDelightApi.java");
        assertTrue(source.contains("dispatchPlayerGui("), "the API must route recipe GUIs through one helper");
        assertTrue(source.contains("scheduler.isOwnedByCurrentRegion(player)"),
                "an owned player must open inline");
        assertTrue(source.contains("scheduler.runForEntity(player"), "a foreign thread must hop to the player");
        for (String entry : new String[]{"openRecipeBook(Player player, RecipeFiller filler)",
                "openRecipeBook(Player player, String typeId, RecipeFiller filler)",
                "openRecipeEditor(Player player, String typeId, String recipeId)"}) {
            int at = source.indexOf(entry);
            assertTrue(at >= 0, "missing entry point: " + entry);
            String body = source.substring(at, Math.min(source.length(), at + 450));
            assertTrue(body.contains("dispatchPlayerGui("), entry + " must use the dispatch helper");
        }
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
