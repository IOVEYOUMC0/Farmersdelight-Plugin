package com.huidu.farmersdelight.gui.editor;

import com.huidu.farmersdelight.util.yaml.BukkitYamlValueWriter;
import com.huidu.farmersdelight.util.yaml.YamlFileTransaction;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecipeOverrideTransactionTest {
    @TempDir Path directory;

    @Test void queuedEditsMergeDifferentRecipeIdsAndPreserveComments() throws Exception {
        Path file = directory.resolve("recipes.yml");
        Files.writeString(file, "# operator comment\nexternal-overrides:\n  cooking_pot:\n  - addon:old\n");
        var add = RecipeEditorStore.overrideEdit(file.toFile(), "cooking_pot", "addon:first", true);
        var next = RecipeEditorStore.overrideEdit(file.toFile(), "cooking_pot", "addon:second", true);
        var remove = RecipeEditorStore.overrideEdit(file.toFile(), "cooking_pot", "addon:old", false);
        for (var edit : List.of(add, next, remove)) {
            assertTrue(YamlFileTransaction.apply(file, List.of(edit), BukkitYamlValueWriter.INSTANCE,
                    BukkitYamlValueWriter.check(List.of(edit))).written());
        }
        assertEquals(List.of("addon:first", "addon:second"),
                YamlConfiguration.loadConfiguration(file.toFile()).getStringList("external-overrides.cooking_pot"));
        assertTrue(Files.readString(file).startsWith("# operator comment\n"));
    }

    @Test void aPlanDoesNotReadOrCreateFilesAndInvalidYamlIsNotReplaced() throws Exception {
        Path file = directory.resolve("recipes.yml");
        var edit = RecipeEditorStore.overrideEdit(file.toFile(), "cooking_pot", "addon:first", true);
        assertFalse(Files.exists(file));
        String invalid = "external-overrides:\n  cooking_pot: [unfinished\n";
        Files.writeString(file, invalid);
        assertFalse(YamlFileTransaction.apply(file, List.of(edit), BukkitYamlValueWriter.INSTANCE,
                BukkitYamlValueWriter.check(List.of(edit))).written());
        assertEquals(invalid, Files.readString(file));
    }
}
