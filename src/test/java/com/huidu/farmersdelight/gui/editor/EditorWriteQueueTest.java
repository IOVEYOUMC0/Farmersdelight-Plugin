package com.huidu.farmersdelight.gui.editor;

import com.huidu.farmersdelight.util.yaml.BukkitYamlValueWriter;
import com.huidu.farmersdelight.util.yaml.YamlFileTransaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editor's disk half: a full queue is a failure the caller shows and never a silent drop, the file work
 * happens away from the owner thread and reports back on it, and writes still in flight at shutdown are either
 * finished within the budget or counted so the shutdown can report them.
 *
 * The file work is the real transaction against a real file; only the pool and the owner hop are stand-ins.
 */
class EditorWriteQueueTest {

    private static final String FILE_TEXT = "cooking_pot_recipes:\n  soup:\n    result: minecraft:bowl\n";

    @TempDir
    Path tmp;

    @Test
    void aFullQueueIsRefusedWithoutTouchingTheFile() throws IOException {
        Path file = tmp.resolve("recipes.yml");
        Files.writeString(file, FILE_TEXT);
        EditorWriteQueue queue = new EditorWriteQueue(task -> false);
        List<String> completions = new ArrayList<>();

        boolean accepted = queue.submit(plan(file, "minecraft:apple"), Runnable::run, completions::add);

        assertFalse(accepted, "the caller has to be told the write was refused");
        assertTrue(completions.isEmpty(), "a refused write reports nothing: the caller shows the busy message");
        assertEquals(FILE_TEXT, Files.readString(file), "a refused write leaves the file exactly as it was");
        assertEquals(0, queue.pendingWrites());
    }

    @Test
    void aWriteRunsOffTheOwnerThreadAndReportsBackOnIt() throws Exception {
        Path file = tmp.resolve("recipes.yml");
        Files.writeString(file, FILE_TEXT);
        ExecutorService pool = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "fd-async-pool"));
        ExecutorService owner = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "fd-owner"));
        try {
            EditorWriteQueue queue = new EditorWriteQueue(task -> {
                pool.execute(task);
                return true;
            });
            List<String> reported = new ArrayList<>();
            CountDownLatch done = new CountDownLatch(1);

            assertTrue(queue.submit(plan(file, "minecraft:apple"), task -> owner.execute(task), reason -> {
                reported.add(Thread.currentThread().getName() + ":" + reason);
                done.countDown();
            }));

            assertTrue(done.await(10, TimeUnit.SECONDS), "the write has to report back");
            assertEquals(1, reported.size());
            assertTrue(reported.getFirst().startsWith("fd-owner"),
                    "the completion runs on the thread that owns the menu: " + reported.getFirst());
            assertTrue(reported.getFirst().endsWith(":null"), "a written file reports no reason");
            assertTrue(Files.readString(file).contains("minecraft:apple"));
            assertEquals(0, queue.pendingWrites());
        } finally {
            pool.shutdownNow();
            owner.shutdownNow();
        }
    }

    @Test
    void aFailedWriteIsReportedAndLeavesTheFile() throws IOException {
        Path file = tmp.resolve("recipes.yml");
        byte[] original = FILE_TEXT.getBytes(StandardCharsets.UTF_8);
        Files.write(file, original);
        EditorWriteQueue queue = new EditorWriteQueue(task -> {
            task.run();
            return true;
        });
        List<String> reported = new ArrayList<>();
        YamlFileTransaction.Edit edit = new YamlFileTransaction.SetValue(
                List.of("cooking_pot_recipes", "soup"), Map.of("result", "minecraft:apple"));
        EditorWriteQueue.EditPlan refusing = new EditorWriteQueue.EditPlan(file, List.of(edit),
                BukkitYamlValueWriter.INSTANCE, text -> "simulated refusal");

        assertTrue(queue.submit(refusing, Runnable::run, reported::add));

        assertEquals(List.of("simulated refusal"), reported, "the failure reaches the caller with its reason");
        assertTrue(java.util.Arrays.equals(original, Files.readAllBytes(file)),
                "a failed write must not damage the file");
    }

    @Test
    void writesStillInFlightAreCountedAndDrained() throws IOException {
        Path file = tmp.resolve("recipes.yml");
        Files.writeString(file, FILE_TEXT);
        List<Runnable> queued = new ArrayList<>();
        EditorWriteQueue queue = new EditorWriteQueue(task -> {
            queued.add(task);
            return true;
        });

        assertTrue(queue.submit(plan(file, "minecraft:apple"), Runnable::run, reason -> { }));
        assertEquals(1, queue.pendingWrites(), "the write counts as in flight until its task runs");
        assertFalse(queue.drain(30L), "a budget that cannot finish it has to report that");
        assertEquals(1, queue.pendingWrites(), "and the write is still counted, so the shutdown can report it");

        queued.getFirst().run();
        assertEquals(0, queue.pendingWrites());
        assertTrue(queue.drain(1000L), "a drained queue reports that nothing is in flight");
        assertTrue(Files.readString(file).contains("minecraft:apple"));
    }

    @Test
    void theEditorKeepsSerialisationOnTheOwnerThread() throws IOException {
        String store = readSource("gui/editor/RecipeEditorStore.java");
        String submit = between(store, "private boolean submitPlan(", "    /** Editor writes still in flight");
        assertTrue(submit.contains("runForEntity("),
                "the completion has to come back through the player's own scheduler");
        assertFalse(submit.contains("buildCookingPotBody") || submit.contains("buildCuttingBoardBody"),
                "the plan is built before the queue is used, so serialisation stays off the worker");
        assertTrue(store.indexOf("new EditorWriteQueue.EditPlan(") < store.indexOf("writeQueue.submit(plan,"),
                "the plan exists before it is submitted");
        assertTrue(store.contains("plugin.reloadRecipeFiles()") && submit.contains("plugin.reloadRecipeFiles()"),
                "the managers are rebuilt on the owner thread once the write landed");

        String pot = readSource("gui/editor/CookingPotEditorGui.java");
        String board = readSource("gui/editor/CuttingBoardEditorGui.java");
        for (String gui : new String[]{pot, board}) {
            assertTrue(gui.contains("saveCookingPotRecipeAsync(") || gui.contains("saveCuttingBoardRecipeAsync("),
                    "the editor saves through the async entry point");
            assertTrue(gui.contains("gui.editor.feedback.save_busy"),
                    "a refused write has to be visible to the player");
            assertFalse(gui.contains("RecipeEditorView.store().saveCookingPotRecipe(")
                            || gui.contains("RecipeEditorView.store().saveCuttingBoardRecipe("),
                    "the synchronous entry point is no longer used by the editors");
        }

        String plugin = readSource("FarmersDelightPlugin.java");
        assertTrue(plugin.indexOf("plugin.disable_step_shutdown_scheduler")
                        < plugin.indexOf("RecipeEditorView.pendingEditorWrites()"),
                "in-flight editor writes are counted after the scheduler drained its pool");
        assertTrue(plugin.contains("plugin.recipe_saves_pending"));
    }

    // ---------------------------------------------------------------- helpers

    private EditorWriteQueue.EditPlan plan(Path file, String result) {
        YamlFileTransaction.Edit edit = new YamlFileTransaction.SetValue(
                List.of("cooking_pot_recipes", "soup"), Map.of("result", result));
        return new EditorWriteQueue.EditPlan(file, List.of(edit), BukkitYamlValueWriter.INSTANCE,
                BukkitYamlValueWriter.check(List.of(edit)));
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = from < 0 ? -1 : source.indexOf(end, from);
        if (from < 0 || to < 0) {
            throw new AssertionError("source is missing '" + start + "' .. '" + end + "'");
        }
        return source.substring(from, to);
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
