package com.huidu.farmersdelight.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Work spread over several ticks consumes at most one budget per slice, never runs past the end of the batch and
 * always makes progress, so a batch cannot strand itself on a slice of zero entries. The last two tests hold the
 * debug batch runner to that budget and hold the recipe validation to being sliced, because that pass walks every
 * loaded recipe.
 */
class TickBudgetTest {

    private static final Path DEBUG_BATCH_RUNNER = Path.of("src", "debugTools", "java", "com", "huidu",
            "farmersdelight", "debug", "DebugBatchRunner.java");
    private static final Path DEBUG_COMMAND = Path.of("src", "debugTools", "java", "com", "huidu",
            "farmersdelight", "debug", "DebugToolsCommand.java");

    @Test
    void aSliceConsumesAtMostTheBudget() {
        TickBudget.Cursor cursor = TickBudget.at(0, 100, 16);
        assertEquals(16, cursor.sliceEnd());
        assertEquals(16, cursor.advance().done());
        assertEquals(0, cursor.done(), "reading the cursor does not move it");
    }

    @Test
    void aSliceNeverRunsPastTheEndOfTheBatch() {
        assertEquals(100, TickBudget.at(90, 100, 16).sliceEnd(), "the last slice stops at the end");
        assertEquals(100, TickBudget.at(100, 100, 16).done(), "a cursor at the end stays there");
        assertEquals(0, TickBudget.at(-3, 100, 16).done(), "a negative cursor is clamped to the start");
        assertEquals(100, TickBudget.at(500, 100, 16).done(), "a stale cursor is clamped to the end");
        assertTrue(TickBudget.at(100, 100, 16).finished());
        assertFalse(TickBudget.at(99, 100, 16).finished());
    }

    @Test
    void theWholeBatchIsCoveredWithoutLosingOrRepeatingAnEntry() {
        int budget = 10;
        TickBudget.Cursor cursor = TickBudget.at(0, 37, budget);
        int slices = 0;
        while (!cursor.finished()) {
            TickBudget.Cursor next = cursor.advance();
            int consumed = next.done() - cursor.done();
            assertTrue(consumed >= 1 && consumed <= budget,
                    "one slice consumes one to " + budget + " entries, got " + consumed);
            cursor = next;
            slices++;
        }
        assertEquals(37, cursor.done(), "every entry is consumed exactly once");
        assertEquals(4, slices, "37 entries at 10 per slice need four slices");
    }

    @Test
    void aBudgetBelowOneStillAdvances() {
        assertEquals(1, TickBudget.at(0, 10, 0).advance().done(), "a zero budget still consumes one entry");
        assertEquals(1, TickBudget.at(0, 10, -5).advance().done(), "a negative budget still consumes one entry");
        assertEquals(10, TickBudget.at(0, 10, 0).remaining(), "the clamped budget leaves the whole batch");
        assertEquals(9, TickBudget.at(0, 10, 0).advance().remaining(), "one entry of the batch was consumed");
    }

    @Test
    void theDebugBatchSlicesItsWorkThroughTheBudget() throws IOException {
        String runner = read(DEBUG_BATCH_RUNNER);
        assertTrue(runner.contains("TickBudget.at("), "the batch slice must take its end index from TickBudget");
        assertTrue(runner.contains("sliceEnd()"), "the batch slice must stop at the cursor's slice end");
        assertFalse(runner.contains("Math.min(count, cursor + 16)"),
                "the slice bound must not be a literal hidden in the runner");
    }

    @Test
    void theRecipeValidationIsSlicedAndResolvesEachCandidateOnce() throws IOException {
        String source = read(DEBUG_COMMAND);
        String validation = methodBody(source, "private void recipeValidate(Player player)");
        assertTrue(validation.contains("batches.start(player,"),
                "recipe validation must run one recipe per slice through the batch runner");
        assertFalse(validation.contains("for (RecipeIngredient ingredient :"),
                "the per-recipe checks must not run inside the command entry");
        // A candidate set is enumerated once per command and handed down: the declaration plus the tab
        // completion and the two recipe handlers. Nothing rebuilds it on the way in.
        assertTrue(count(source, "recipeChoices(") <= 4,
                "recipeChoices must not gain a second call site inside one command");
        assertFalse(source.contains("new ArrayList<>(recipeChoices"),
                "the candidate set must not be copied in full just to shuffle it");
        assertFalse(source.contains("recipeIds(String"),
                "the id list must be derived from the set the command already enumerated");
        // A recipe is read into placement form once per command: the declaration and the two handlers.
        assertTrue(count(source, "resolveRecipeSetup(") <= 3,
                "a recipe must not be resolved a second time for the same command");
    }

    private static int count(String source, String token) {
        int found = 0;
        int at = source.indexOf(token);
        while (at >= 0) {
            found++;
            at = source.indexOf(token, at + token.length());
        }
        return found;
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing method: " + signature);
        int next = source.indexOf("\n    private ", start + signature.length());
        return next < 0 ? source.substring(start) : source.substring(start, next);
    }

    private static String read(Path path) throws IOException {
        Path resolved = path;
        if (!Files.isRegularFile(resolved)) {
            Path nested = Path.of("FarmersDelight").resolve(path);
            if (Files.isRegularFile(nested)) {
                resolved = nested;
            }
        }
        assertTrue(Files.isRegularFile(resolved), "missing source file: " + path + " (working directory "
                + Path.of("").toAbsolutePath() + ")");
        return Files.readString(resolved).replace("\r\n", "\n");
    }
}
