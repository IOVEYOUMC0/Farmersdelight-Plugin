package com.huidu.farmersdelight.block.behavior;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cutting feedback has four sound tiers, in the upstream order: recipe, tool table, the ingredient's own
 * block break sound, then the configured fallback. The first three only fill the previous gap, so the order is
 * part of the behaviour.
 */
class CuttingBoardSoundFallbackTest {

    @Test
    void soundTiersKeepTheUpstreamOrder() throws IOException {
        String cutter = read("src/main/java/com/huidu/farmersdelight/block/behavior/CuttingBoardCutter.java");
        int recipe = cutter.indexOf("recipe.getSound()");
        int tool = cutter.indexOf("sounds.matchTool(tool)");
        int ingredient = cutter.indexOf("ingredientBreakSound(storedItem)");
        int fallback = cutter.indexOf("sounds.fallback()");
        assertTrue(recipe > 0, "the recipe sound has to stay the first tier");
        assertTrue(tool > recipe, "the tool table has to come after the recipe sound");
        assertTrue(ingredient > tool, "the ingredient block sound has to come after the tool table");
        assertTrue(fallback > ingredient, "the configured fallback has to be the last tier");
    }

    @Test
    void ingredientSoundComesFromTheBlockSoundGroup() throws IOException {
        String cutter = read("src/main/java/com/huidu/farmersdelight/block/behavior/CuttingBoardCutter.java");
        assertTrue(cutter.contains("type.createBlockData().getSoundGroup().getBreakSound()"));
        assertTrue(cutter.contains("!type.isBlock()"), "only block ingredients contribute a block sound");
    }

    @Test
    void soundsExposeAnUnmatchedLookupAndTheConfiguredFallback() throws IOException {
        String sounds = read("src/main/java/com/huidu/farmersdelight/config/CuttingBoardSounds.java");
        assertTrue(sounds.contains("public ToolSoundTable.Entry matchTool("));
        assertTrue(sounds.contains("public ToolSoundTable.Entry fallback("));
    }

    private static String read(String relative) throws IOException {
        for (String prefix : new String[]{"", "FarmersDelight/"}) {
            Path path = Path.of(prefix + relative);
            if (Files.isRegularFile(path)) return Files.readString(path);
        }
        throw new IOException("source not found: " + relative);
    }
}
