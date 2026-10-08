package com.huidu.farmersdelight.pack;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the single merged recipe file: every recipe lives under the root key the loader claims, the item and
 * block files carry none of them, the entries sit in the agreed type order, and the merge changed no field.
 *
 * The raw block of every recipe is checked in as a baseline, so this compares the current pack against the
 * text that existed before the recipes were moved out rather than against a count that could be kept while
 * fields drift.
 */
class RecipeFileMergeTest {

    private static final Path CFG = Path.of(
            "src/main/resources/craftengine/farmersdelight/configuration");
    private static final Path MERGED = CFG.resolve("recipes.yml");
    private static final Path BASELINE = Path.of("src/test/resources/recipe-split-baseline.yml");
    private static final String ROOT = "recipes";
    private static final List<String> TYPE_ORDER = List.of(
            "shaped", "shapeless", "smelting", "blasting", "smoking", "campfire_cooking",
            "smithing_transform");
    private static final List<String> REMOVED_FILES = List.of(
            "campfire_recipes.yml", "crafting_recipes.yml", "smithing_recipes.yml",
            "skewer_recipes.yml", "storage_block_recipes.yml", "stove_recipes.yml");
    private static final List<String> ITEM_AND_BLOCK_FILES = List.of(
            "items.yml", "blocks.yml", "knives.yml", "mushrooms.yml", "rope.yml");

    @Test
    void everyRecipeIdIsDefinedExactlyOnceInTheSingleFile() throws Exception {
        Map<String, String> baseline = baseline();
        assertEquals(193, baseline.size(), "the pack ships 193 recipes");

        List<String> holders = new ArrayList<>();
        for (String name : configurationFiles()) {
            if (!recipesIn(CFG.resolve(name), ROOT).isEmpty()) {
                holders.add(name);
            }
        }
        assertEquals(List.of("recipes.yml"), holders,
                "every recipe has to live in this one file; a second holder is a regression");

        assertEquals(new TreeSet<>(baseline.keySet()), new TreeSet<>(recipesIn(MERGED, ROOT).keySet()),
                "the merge may not add or drop a recipe id");
    }

    @Test
    void theRemovedFilesAreGoneAndTheItemFilesKeepNoRecipes() throws Exception {
        for (String name : REMOVED_FILES) {
            assertFalse(Files.exists(CFG.resolve(name)),
                    name + " must not come back: the recipes live in recipes.yml only");
        }
        for (String name : ITEM_AND_BLOCK_FILES) {
            Path file = CFG.resolve(name);
            assertTrue(Files.exists(file), name + " has to stay");
            assertTrue(Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                            .noneMatch(line -> line.equals(ROOT + ":")),
                    name + " must not carry the recipes root key: moving the section back is a regression");
            assertTrue(recipesIn(file, ROOT).isEmpty(), name + " still exposes recipe entries");
        }
    }

    @Test
    void theMergeKeepsEveryFieldOfEveryRecipe() throws Exception {
        Map<String, String> current = recipesIn(MERGED, ROOT);
        for (Map.Entry<String, String> entry : baseline().entrySet()) {
            String id = entry.getKey();
            String now = current.get(id);
            assertNotNull(now, id + " is missing from the merged file");
            assertEquals(entry.getValue(), now, id + " changed while it was merged");

            YamlConfiguration before = config(ROOT + ":\n" + entry.getValue());
            YamlConfiguration after = config(ROOT + ":\n" + now);
            for (String field : List.of("result.id", "result.count", "cookingtime", "experience", "type")) {
                assertEquals(before.get(id + "." + field), after.get(id + "." + field),
                        id + " field " + field + " changed");
            }
        }
    }

    @Test
    void theEntriesFollowTheTypeOrderAndAreSortedById() throws Exception {
        Map<String, String> entries = recipesIn(MERGED, ROOT);
        Map<String, List<String>> byType = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            byType.computeIfAbsent(typeOf(entry.getValue()), key -> new ArrayList<>()).add(entry.getKey());
        }
        assertEquals(TYPE_ORDER, List.copyOf(byType.keySet()),
                "the types have to appear in the agreed order, and no other type may show up");

        List<String> seen = new ArrayList<>();
        for (Map.Entry<String, List<String>> group : byType.entrySet()) {
            List<String> ids = group.getValue();
            List<String> sorted = new ArrayList<>(ids);
            sorted.sort(String::compareTo);
            assertEquals(sorted, ids, group.getKey() + " entries are not in dictionary order");
            seen.addAll(ids);
        }
        assertEquals(List.copyOf(entries.keySet()), seen, "the file order must be the read order");
        assertEquals(List.of("shaped", "shapeless", "smelting", "blasting", "smoking",
                        "campfire_cooking", "smithing_transform"), TYPE_ORDER);
    }

    @Test
    void theLoaderReadsOnlyTheRecipesRootKey(@TempDir Path temp) throws Exception {
        assertEquals(ROOT + ":", Files.readAllLines(MERGED, StandardCharsets.UTF_8).get(0),
                "the file has to start with the claimed root key, which is what the loader reads");

        Path renamed = temp.resolve("renamed.yml");
        Files.writeString(renamed, Files.readString(MERGED, StandardCharsets.UTF_8)
                .replaceFirst("^recipes:", "recipes_renamed:"), StandardCharsets.UTF_8);
        assertTrue(recipesIn(renamed, ROOT).isEmpty(),
                "a renamed root key must make the loader see no recipes at all");
        assertEquals(193, recipesIn(renamed, "recipes_renamed").size(),
                "the entries are still there, only under a key the loader does not read");
        assertEquals(193, recipesIn(MERGED, ROOT).size());
    }

    private static String typeOf(String block) {
        for (String line : block.split("\n")) {
            String stripped = line.strip();
            if (stripped.startsWith("type:")) {
                return stripped.substring("type:".length()).strip();
            }
        }
        return "";
    }

    private static YamlConfiguration config(String text) throws InvalidConfigurationException {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.loadFromString(text);
        return configuration;
    }

    /** The checked in pre-move text of every recipe, keyed by recipe id. */
    private static Map<String, String> baseline() throws IOException {
        assertTrue(Files.exists(BASELINE), BASELINE.toString());
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(BASELINE.toFile());
        Map<String, String> blocks = new LinkedHashMap<>();
        for (String id : configuration.getKeys(false)) {
            blocks.put(id, configuration.getString(id));
        }
        return blocks;
    }

    private static List<String> configurationFiles() throws IOException {
        try (Stream<Path> files = Files.list(CFG)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".yml"))
                    .sorted()
                    .toList();
        }
    }

    /**
     * The recipe entries a file exposes under the given root key, keyed by id and holding the raw text of the
     * entry in file order. Mirrors what the loader does: only the root key of the file is read.
     */
    private static Map<String, String> recipesIn(Path file, String rootKey) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        int start = lines.indexOf(rootKey + ":");
        if (start < 0) {
            return Map.of();
        }
        int end = lines.size();
        for (int i = start + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.isEmpty() && !line.startsWith(" ") && !line.startsWith("#")) {
                end = i;
                break;
            }
        }
        List<Integer> starts = new ArrayList<>();
        for (int i = start + 1; i < end; i++) {
            String line = lines.get(i);
            if (line.length() > 2 && line.startsWith("  ") && !line.startsWith("   ")) {
                starts.add(i);
            }
        }
        Map<String, String> blocks = new LinkedHashMap<>();
        for (int n = 0; n < starts.size(); n++) {
            int from = starts.get(n);
            int to = n + 1 < starts.size() ? starts.get(n + 1) : end;
            List<String> block = new ArrayList<>(lines.subList(from, to));
            while (!block.isEmpty() && block.getLast().isBlank()) {
                block.removeLast();
            }
            String id = lines.get(from).strip();
            blocks.put(id.substring(0, id.length() - 1), String.join("\n", block));
        }
        return blocks;
    }
}
