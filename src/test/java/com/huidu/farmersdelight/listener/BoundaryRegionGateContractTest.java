package com.huidu.farmersdelight.listener;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source contract: both boundary interaction listeners check region ownership before reading the clicked block,
 * because the event runs on the player's region and the block may belong to another one.
 */
class BoundaryRegionGateContractTest {

    @Test
    void cakeSliceGatesOwnershipBeforeReadingTheBlock() throws IOException {
        String source = read("src/main/java/com/huidu/farmersdelight/listener/CakeSliceListener.java");
        int gate = source.indexOf("if (!ownsRegion(block))");

        assertTrue(gate > 0);
        assertTrue(gate < source.indexOf("block.getType()"), "the material read must come after the gate");
        assertTrue(gate < source.indexOf("isCustomBlock(block)"), "the CraftEngine state read must come after");
        assertTrue(gate < source.indexOf("block.getBlockData()"), "the block data read must come after");
        assertEquals(1, occurrences(source, "ownsRegion(block)"),
                "one gate at the top; a later check in the same method cannot be reached by another thread");
    }

    @Test
    void pumpkinCarveGatesOwnershipBeforeReadingTheBlock() throws IOException {
        String source = read("src/main/java/com/huidu/farmersdelight/listener/PumpkinCarveListener.java");
        int gate = source.indexOf("if (!ownsRegion(block))");

        assertTrue(gate > 0);
        assertTrue(gate < source.indexOf("block.getType()"), "the material read must come after the gate");
        assertTrue(gate < source.indexOf("isCustomBlock(block)"), "the CraftEngine state read must come after");
        assertEquals(1, occurrences(source, "ownsRegion(block)"),
                "one gate at the top; a later check in the same method cannot be reached by another thread");
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
