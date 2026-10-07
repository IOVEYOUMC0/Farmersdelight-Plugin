package com.huidu.farmersdelight.recipe;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the parse store is wired: every recipe entry of both managers is parsed through the cache, and the
 * readiness passes advance the content generation so a pass that first sees new CraftEngine content never
 * reuses what was parsed against the old one.
 *
 * The managers and the coordinator need a running server, so the wiring is pinned on the source text; what the
 * cache does is covered by the tests that run it.
 */
class RecipeParseCacheWiringTest {

    private static final String POT = "recipe/CookingPotRecipeManager.java";
    private static final String BOARD = "recipe/CuttingBoardRecipeManager.java";
    private static final String COORDINATOR = "CraftEngineReadinessCoordinator.java";

    @Test
    void everyRecipeEntryIsParsedThroughTheCache() throws IOException {
        for (String manager : new String[]{POT, BOARD}) {
            String source = readSource(manager);

            assertTrue(source.contains("private final ParsedRecipeCache<"),
                    manager + " holds one parse store");
            assertTrue(source.contains("parseCache.parse(RecipeContentEpoch.current(), source, recipeId,"),
                    manager + " keys parses on the content generation, the source, the entry and its values");
            assertTrue(source.contains("String.valueOf(section.getValues(true))"),
                    manager + " fingerprints the entry's own values");
            assertTrue(source.contains("parseCached(OWN_FILE_SOURCE, recipeId, section"),
                    manager + " parses its own file through the cache");
            assertTrue(source.contains("parseCached(source, recipeId, section"),
                    manager + " parses pack entries through the cache");
            assertEquals(1, count(source, "parseRecipe(recipeId, section"),
                    manager + " parses an entry in exactly one place: the cache's miss path");
            assertTrue(source.indexOf("parseCached(") < source.indexOf("parseRecipe(recipeId, section"),
                    manager + " keeps that one parse behind the cache");
        }
    }

    @Test
    void everyReadinessPassStartsANewContentGeneration() throws IOException {
        String source = readSource(COORDINATOR);

        assertEquals(3, count(source, "RecipeContentEpoch.advance();"),
                "the startup, deferred and reload passes each start a new generation");
        assertTrue(source.indexOf("RecipeContentEpoch.advance();")
                        < source.indexOf("loadRecipesThenPublish("),
                "the generation is advanced before the pass loads its recipes");
    }

    private static int count(String source, String needle) {
        int found = 0;
        int index = source.indexOf(needle);
        while (index >= 0) {
            found++;
            index = source.indexOf(needle, index + needle.length());
        }
        return found;
    }

    private static String readSource(String relative) throws IOException {
        Path found = locate(relative);
        if (found == null) {
            throw new AssertionError("source has to be reachable from the test working directory: " + relative);
        }
        return Files.readString(found);
    }

    private static Path locate(String relative) {
        String[] prefixes = {"src/main/java/com/huidu/farmersdelight/",
                "FarmersDelight/src/main/java/com/huidu/farmersdelight/"};
        Path cursor = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 4 && cursor != null; depth++) {
            for (String prefix : prefixes) {
                Path candidate = cursor.resolve(prefix + relative);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
            cursor = cursor.getParent();
        }
        return null;
    }
}
