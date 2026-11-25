package com.huidu.farmersdelight.block.behavior;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Harvesting in place must not let the old crop's removal hook replace the new crop with rope. */
class TomatoVineRemovalGuardTest {

    @Test
    void aReplacementCropBlocksTheRopePlacement() throws IOException {
        String source = read("src/main/java/com/huidu/farmersdelight/block/behavior/TomatoVineBlockBehavior.java");
        int method = source.indexOf("public void affectNeighborsAfterRemoval(");
        assertTrue(method >= 0, "affectNeighborsAfterRemoval must exist");
        int end = source.indexOf("private ", method + 1);
        String body = source.substring(method, end > method ? end : source.length());
        int current = body.indexOf("getCustomBlockState(");
        int ropePlace = body.indexOf("CraftEngineBlocks.place(placeLoc");
        assertTrue(current >= 0, "the removal hook must read the replacement block at the position");
        assertTrue(ropePlace > current, "the replacement guard must run before placing rope");
        assertTrue(body.contains("!current.isEmpty()"), "the guard must distinguish a replacement block from air");
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
