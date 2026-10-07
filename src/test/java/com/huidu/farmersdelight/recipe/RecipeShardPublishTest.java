package com.huidu.farmersdelight.recipe;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wiring a sharded recipe reload has to keep: every source of one reload goes into one queued round, and
 * the derived indexes are published only from that round's tail, as a snapshot.
 *
 * The behaviour of the round and of the driver is covered offline by RecipeRegistrationRoundTest and
 * RecipeRegistrationDriverTest. A recipe manager itself cannot be built in a test — parsing an entry needs a
 * live CraftEngine item registry — so the remaining two facts, that the managers queue all their sources into
 * one round and that they publish from its tail only, are pinned on the source text.
 */
class RecipeShardPublishTest {

    private static final List<String> MANAGERS = List.of(
            "CuttingBoardRecipeManager.java",
            "CookingPotRecipeManager.java");

    // A start per source is exactly what cancelled the previous source's remaining entries.
    @Test
    void theFileLoaderOnlyQueuesOneSourceAndNeverStartsARound() throws IOException {
        String loader = readSource("RecipeFileLoader.java");

        assertFalse(loader.contains("recipeRegistrations().start("),
                "the loader may queue a source but never start a round of its own");
        assertTrue(loader.contains("recipeSectionSegment("), "it prepares one queued source per file");
        assertTrue(loader.contains("return new RecipeRegistrationRound.Segment(ids, pass::step, pass::finish);"),
                "the source carries its entries, their work and its own tail");
    }

    @Test
    void eachManagerQueuesEverySourceOfOneReloadIntoASingleRound() throws IOException {
        for (String manager : MANAGERS) {
            String source = readSource(manager);

            assertEquals(1, count(source, "recipeRegistrations().start("),
                    manager + " has to start one round for the whole reload, not one round per source");
            assertTrue(source.contains("recipeRegistrations().start(this, segments,"),
                    manager + " owns its round, so a manager rebuilt next in the same pass cannot cancel it");

            int ownFile = source.indexOf("segments.add(ownFile)");
            int packLoop = source.indexOf("for (PackSections.Section packSection");
            assertTrue(ownFile > 0 && packLoop > ownFile,
                    manager + " queues the plugin's own file before every pack section");
        }
    }

    // The CraftEngine readiness pass keeps going in the same tick after the managers are rebuilt: it warms the
    // recipe-backed caches and prints the content summary out of the published set. A load with nothing
    // published yet therefore runs whole, or that pass reads zero recipes.
    @Test
    void theFirstLoadRunsWholeSoTheReadinessPassSeesIt() throws IOException {
        for (String manager : MANAGERS) {
            String source = readSource(manager);

            assertTrue(source.contains(
                            "RecipeRegistrationBudget.forLoad(recipes.size(), plugin.recipeRegistrationBudget())"),
                    manager + " has to run a load with nothing published yet whole, not on the per-tick budget");
        }
    }

    @Test
    void eachManagerPublishesOnlyFromTheRoundTail() throws IOException {
        for (String manager : MANAGERS) {
            String source = readSource(manager);

            assertTrue(source.contains("() -> publishLoadedSet(pending)"),
                    manager + " hands the publish to the round as its completion callback");
            assertTrue(source.contains("private void publishLoadedSet(PendingLoad pending)"),
                    manager + " publishes from the round tail, never from loadRecipes mid-registration");
        }
    }

    @Test
    void eachManagerPublishesASnapshotRatherThanTheRoundBuffer() throws IOException {
        for (String manager : MANAGERS) {
            String source = readSource(manager);

            assertTrue(source.contains("this.recipes = Collections.unmodifiableMap(new LinkedHashMap<>(newRecipes));"),
                    manager + " publishes an immutable snapshot, so no later tick can write into it");
            assertFalse(source.contains("this.recipes = newRecipes;"),
                    manager + " must not publish the round's own buffer");
            assertFalse(source.contains("this.recipes = pending.recipes;"),
                    manager + " must not publish the round's own buffer");
        }
    }

    private static int count(String text, String needle) {
        int found = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            found++;
            from += needle.length();
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
        String[] prefixes = {"FarmersDelight/src/main/java/com/huidu/farmersdelight/recipe/",
                "src/main/java/com/huidu/farmersdelight/recipe/"};
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
