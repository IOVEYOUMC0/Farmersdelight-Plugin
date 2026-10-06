package com.huidu.farmersdelight.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the heat source table the skewer checks: the set has to keep every source the upstream mod accepts, and a
 * campfire has to keep counting only while it is lit. Dropping either half is what makes "it only works next to a
 * magma block" come back.
 *
 * Asserted on the source because the table is built from Bukkit materials and block data, which no test here can
 * instantiate.
 */
class HeatSourceDefaultsTest {

    @Test
    void theDefaultsCoverEveryHeatSourceTheUpstreamModAccepts() throws IOException {
        String source = read("HeatSourceConfig.java");
        int defaults = source.indexOf("public void loadDefaults()");
        assertTrue(defaults > 0, "the defaults block has to exist");
        String block = source.substring(defaults, source.indexOf("\n    }", defaults));

        for (String entry : new String[]{"minecraft:magma_block", "minecraft:lava", "minecraft:fire",
                "minecraft:soul_fire", "minecraft:campfires", "farmersdelight:stove[fire:true]"}) {
            assertTrue(block.contains(entry), "the defaults have to keep " + entry + ": " + block);
        }
    }

    @Test
    void aCampfireOnlyCountsWhileItIsLit() throws IOException {
        String source = read("HeatSourceConfig.java");
        assertTrue(source.contains("vanillaLitBlocks.add(Material.CAMPFIRE)"),
                "a campfire has to be matched through the lit-state table");
        assertTrue(source.contains("vanillaLitBlocks.add(Material.SOUL_CAMPFIRE)"));
        assertTrue(source.contains("instanceof Lightable") && source.contains("lightable.isLit()"),
                "and the lookup has to consult the block's lit state, or an unlit campfire would cook");
    }

    private static String read(String relative) throws IOException {
        Path found = locate(relative);
        if (found == null) {
            throw new AssertionError("source has to be reachable from the test working directory: " + relative);
        }
        return Files.readString(found);
    }

    private static Path locate(String relative) {
        String[] prefixes = {"FarmersDelight/src/main/java/com/huidu/farmersdelight/config/",
                "src/main/java/com/huidu/farmersdelight/config/"};
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
