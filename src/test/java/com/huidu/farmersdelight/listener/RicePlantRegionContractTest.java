package com.huidu.farmersdelight.listener;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-contract checks: every block read this listener schedules is handed to the block's region and
 * re-checks the chunk is still loaded inside the task, so a queued update cannot load an unloaded chunk.
 */
class RicePlantRegionContractTest {

    @Test
    void immediateBlockTasksGoThroughTheLoadedChunkDispatch() throws IOException {
        String source = read("src/main/java/com/huidu/farmersdelight/listener/RicePlantListener.java");
        assertTrue(source.contains("RegionTasks.runAtLoadedChunk(plugin.scheduler(), world,"));
        assertFalse(source.contains("plugin.scheduler().runAt("),
                "block work must not be scheduled without the chunk-loaded re-check");
    }

    @Test
    void delayedBlockTasksRecheckTheChunkBeforeReadingTheBlock() throws IOException {
        String source = read("src/main/java/com/huidu/farmersdelight/listener/RicePlantListener.java");
        int helper = source.indexOf("private void runLaterAtBlock(");
        assertTrue(helper > 0);
        assertTrue(source.indexOf("task.accept(world.isChunkLoaded(chunkX, chunkZ))", helper) > helper,
                "the loaded flag must be computed inside the delayed task");
        assertTrue(source.contains("runLaterAtBlock(location, 1L, chunkLoaded -> {"));
        assertEquals(1, occurrences(source, "plugin.scheduler().runLaterAt("),
                "the delayed block tasks must share the guarded helper");
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        int index = source.indexOf(needle);
        while (index >= 0) {
            count++;
            index = source.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private static String read(String relative) throws IOException {
        for (String prefix : new String[]{"", "FarmersDelight/"}) {
            Path path = Path.of(prefix + relative);
            if (Files.isRegularFile(path)) return Files.readString(path);
        }
        throw new IOException("source not found: " + relative);
    }
}
