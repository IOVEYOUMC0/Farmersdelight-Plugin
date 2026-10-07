package com.huidu.farmersdelight.recipe;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the id rules have to be wired: the discovery command resolves what an operator typed before it
 * checks or stores it and shows namespaced ids, and the editor resolves before it looks an existing recipe
 * up, or a namespaced spelling would open a second recipe and save a duplicate.
 *
 * The rules themselves are covered in RecipeIdsTest; the command and the editor cannot run offline, so the
 * wiring is pinned on the source text.
 */
class RecipeIdWiringTest {

    @Test
    void theDiscoveryCommandResolvesTheTypedIdBeforeUsingIt() throws IOException {
        String command = readSource("command/RecipeSubCommand.java");

        assertTrue(command.contains("RecipeIds.canonical(typeId, recipeToken,"),
                "the typed recipe id has to resolve to the stored one before it is used");
        assertTrue(command.indexOf("RecipeIds.canonical(typeId, recipeToken,") < command.indexOf("manager.unlock("),
                "and before it is stored");
        assertFalse(command.contains("manager.isKnownRecipe(typeId, recipeToken)"),
                "checking the raw token is what rejected the namespaced spelling");
    }

    @Test
    void theDiscoveryListShowsNamespacedIds() throws IOException {
        String command = readSource("command/RecipeSubCommand.java");

        assertTrue(command.contains("shown.add(RecipeIds.displayId(typeId, recipeId))"),
                "the list prints the namespaced form of every id");
    }

    @Test
    void tabCompletionOffersTheNamespacedFormFirstAndKeepsTheStoredOne() throws IOException {
        String command = readSource("command/RecipeSubCommand.java");

        assertTrue(command.contains("String namespaced = RecipeIds.displayId(typeId, recipeId)"),
                "completion builds the namespaced form");
        assertTrue(command.contains("if (!namespaced.equals(recipeId))"),
                "and keeps the stored spelling offered after it");
        assertTrue(command.contains("RecipeDiscoveryManager.TYPE_COOKING_POT, \"cooking_pot\""),
                "the two built-in types are offered namespaced and bare");
    }

    @Test
    void theEditorResolvesTheSpellingBeforeLookingTheRecipeUp() throws IOException {
        String editor = readSource("gui/editor/RecipeEditorView.java");

        assertTrue(editor.contains("RecipeIds.canonical(type, recipeId, new HashSet<>(knownIds))"),
                "the editor resolves what the operator typed to the stored id");
        assertTrue(editor.indexOf("resolveStoredId(type, recipeId,") < editor.indexOf("getRecipe(storedId)"),
                "and resolves it before looking the recipe up");
        assertFalse(editor.contains("getRecipe(recipeId)"),
                "looking the raw token up is what opened a second recipe under its namespaced spelling");
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
