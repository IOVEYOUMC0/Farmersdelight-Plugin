package com.huidu.farmersdelight.recipe;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the publication contract is wired: every readiness pass opens the window itself and runs its
 * recipe-dependent work only once the publication arrived, and every field a manager's publish writes is part
 * of the state a failed pass puts back.
 *
 *
 * The coordinator and the managers need a running server, so the wiring and the field coverage are pinned on
 * the source text; what the watch and the rollback do is covered by the tests that run them.
 */
class RecipePublicationWiringTest {

    private static final String COORDINATOR = "CraftEngineReadinessCoordinator.java";
    private static final String POT = "recipe/CookingPotRecipeManager.java";
    private static final String BOARD = "recipe/CuttingBoardRecipeManager.java";

    @Test
    void everyReadinessPassWaitsForItsPublication() throws IOException {
        String source = readSource(COORDINATOR);

        assertTrue(source.contains(
                        "RecipePublicationWatch watch = plugin.recipeRegistrations().beginPublicationWatch();"),
                "a pass opens the publication window itself");
        assertTrue(source.contains("plugin.recipeRegistrations().endPublicationWatch();"),
                "and closes it once the load call returned");
        assertTrue(source.contains("loadRecipesThenPublish(\"plugin.loading_recipes\","),
                "the start-up pass loads its recipes through the helper that waits");
        assertEquals(2, count(source, "loadRecipesThenPublish(\"plugin.refreshing_recipes_after_ce\","),
                "the ready pass and the reload pass both go through it");
        assertFalse(source.contains("loadRecipesWhenReady(\"plugin.loading_recipes\")"),
                "no pass may load recipes without waiting for the publication");
        assertTrue(source.indexOf("loadRecipesThenPublish(\"plugin.loading_recipes\",") < source.indexOf("warmUp(\"enable\");"),
                "the warm-up runs as the work of that helper, not next to the load");
    }

    @Test
    void aFailedOrOvertakenPassReportsInsteadOfReadingAHalfPublishedSet() throws IOException {
        String source = readSource(COORDINATOR);

        assertTrue(source.indexOf("watch.whenPublished(") < source.indexOf("loadRecipesWhenReady(logKey);"),
                "the continuation is registered before the load, so a load that throws is still reported");
        assertTrue(source.contains("rollback.restore()"), "a failed pass puts the captured sets back");
        assertTrue(source.indexOf("rollback.restore()") < source.indexOf("recipeWork.run();"),
                "and skips the recipe-dependent work rather than reading what did not publish");
        assertTrue(source.contains("pass != readinessPassGeneration.get()"),
                "a pass a newer one overtook reports nothing and rolls back nothing");
        assertTrue(source.contains("plugin.recipe_publication_failed"),
                "the failure is reported to the operator");
    }

    @Test
    void theManagersCaptureEveryFieldTheirPublishWrites() throws IOException {
        for (String manager : new String[]{POT, BOARD}) {
            String source = readSource(manager);

            Set<String> published = publishedFields(source);
            assertFalse(published.isEmpty(), manager + " has to publish something");

            String state = between(source, "public record PublishedState(", ") {");
            String restore = between(source, "public void restorePublished(", "private void invalidateRecipeCaches()");
            for (String field : published) {
                assertTrue(state.contains(field),
                        manager + " publishes " + field + ", so its PublishedState has to carry it");
                assertTrue(restore.contains("state." + field + "()"),
                        manager + " publishes " + field + ", so a rollback has to put it back");
            }
            assertTrue(restore.contains("invalidateRecipeCaches()"),
                    manager + " drops the caches keyed on the recipe set after a rollback");
        }
    }

    /** The this.field = writes of one publish, which is exactly what a published generation owns. */
    private static Set<String> publishedFields(String source) {
        String publish = between(source, "private void publishLoadedSet(", "invalidateRecipeCaches();");
        Set<String> fields = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("this\\.(\\w+)\\s*=").matcher(publish);
        while (matcher.find()) {
            fields.add(matcher.group(1));
        }
        return fields;
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = from < 0 ? -1 : source.indexOf(end, from);
        if (from < 0 || to < 0) {
            throw new AssertionError("source is missing '" + start + "' .. '" + end + "'");
        }
        return source.substring(from, to);
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
