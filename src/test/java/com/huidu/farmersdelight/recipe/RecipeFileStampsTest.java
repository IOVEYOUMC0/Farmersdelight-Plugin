package com.huidu.farmersdelight.recipe;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The reload cost guard: a pass over files that did not change must re-parse nothing.
 *
 *
 * The live defect was a reload that re-read and re-parsed every recipe file on the server thread on every
 * invocation; with the stamp cache the second pass over an untouched file parses zero files.
 */
class RecipeFileStampsTest {

    @TempDir
    Path directory;

    @BeforeEach
    void forgetStamps() {
        RecipeFileStamps.clear();
    }

    @Test
    void anUnchangedFileIsParsedOnceAndThenReused() throws IOException {
        Path file = directory.resolve("cutting_board_recipes.yml");
        Files.writeString(file, "cutting_board_recipes:\n  a:\n    result: minecraft:apple\n",
                StandardCharsets.UTF_8);

        assertEquals(1, pass(file.toFile()), "the first pass has to parse the file");
        assertEquals(0, pass(file.toFile()), "an unchanged file must be reused, not parsed again");
    }

    @Test
    void aChangedFileIsParsedAgain() throws IOException {
        Path file = directory.resolve("cutting_board_recipes.yml");
        Files.writeString(file, "cutting_board_recipes:\n  a:\n    result: minecraft:apple\n",
                StandardCharsets.UTF_8);
        assertEquals(1, pass(file.toFile()));

        Files.writeString(file, "cutting_board_recipes:\n  a:\n    result: minecraft:apple\n  b: {}\n",
                StandardCharsets.UTF_8);
        file.toFile().setLastModified(file.toFile().lastModified() + 2_000L);

        assertEquals(1, pass(file.toFile()), "a bigger or newer file has to be parsed again");
    }

    @Test
    void aMissingFileIsNeverCached() {
        RecipeFileStamps.Stamp stamp = RecipeFileStamps.Stamp.of(directory.resolve("nope.yml").toFile());
        assertNull(RecipeFileStamps.cached("nope.yml", stamp), "a missing file has no parse to reuse");
    }

    /** One loader pass over the file, returning how many parses it needed. */
    private static int pass(java.io.File file) {
        RecipeFileStamps.Stamp stamp = RecipeFileStamps.Stamp.of(file);
        YamlConfiguration cached = RecipeFileStamps.cached(file.getName(), stamp);
        if (cached != null) {
            return 0;
        }
        RecipeFileStamps.noteParse();
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new AssertionError("the test file has to parse", error);
        }
        assertNotNull(yaml);
        RecipeFileStamps.remember(file.getName(), RecipeFileStamps.Stamp.of(file), yaml);
        return 1;
    }
}
