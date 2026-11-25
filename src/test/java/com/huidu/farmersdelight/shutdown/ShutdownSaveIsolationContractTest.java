package com.huidu.farmersdelight.shutdown;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Inner block-data saves must share the disable deadline, and one bad entry must not abort its batch. */
class ShutdownSaveIsolationContractTest {

    @Test
    void blockDataSavesAreRequiredNotSkippable() throws IOException {
        String persistence = read("src/main/java/com/huidu/farmersdelight/WorldDataPersistence.java");
        int method = persistence.indexOf("static void saveAllBlockData");
        int end = persistence.indexOf("static void saveWorldBlockData", method + 1);
        String body = persistence.substring(method, end > method ? end : persistence.length());
        // Once saveAllBlockData has started, all four stores must run: a spent deadline must not skip a later
        // store after an earlier one already wrote. The outer step is still budgeted, so the whole batch is
        // skipped only before it starts.
        assertTrue(body.contains("plugin.runDisableStep("),
                "inner block-data saves must not be skipped individually");
        assertTrue(!body.contains("plugin.runBudgetedDisableStep("),
                "inner block-data saves must not re-check the shared deadline");
    }

    @Test
    void bulkBlockSavesIsolateEachEntry() throws IOException {
        String pot = read("src/main/java/com/huidu/farmersdelight/block/behavior/CookingPotBlockBehavior.java");
        assertTrue(pot.contains("catch (RuntimeException | LinkageError"), "cooking-pot save batch must isolate entries");
        String board = read("src/main/java/com/huidu/farmersdelight/block/behavior/CuttingBoardBlockBehavior.java");
        assertTrue(board.contains("catch (RuntimeException | LinkageError"), "cutting-board save batch must isolate entries");
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
