package com.huidu.farmersdelight.util.yaml;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Editing one entry of a YAML file: everything outside that entry keeps its exact bytes, a refused or failed
 * edit leaves the file untouched, concurrent edits of one file never interleave, and a repeated edit writes
 * nothing the second time.
 */
class YamlFileTransactionTest {

    private static final String SECTION = "cooking_pot_recipes";

    @TempDir
    Path tmp;

    @Test
    void anEditKeepsEveryOtherByteOfTheRealFile() throws IOException {
        Path file = copyOfRealRecipeFile();
        String original = Files.readString(file, StandardCharsets.UTF_8);
        String firstEntry = firstEntryAfterRoot(original);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ingredients", List.of("minecraft:apple"));
        body.put("result", "minecraft:apple");
        List<YamlFileTransaction.Edit> edits =
                List.of(new YamlFileTransaction.SetValue(List.of(SECTION, firstEntry), body));

        YamlFileTransaction.Outcome outcome = YamlFileTransaction.apply(file, edits,
                BukkitYamlValueWriter.INSTANCE, BukkitYamlValueWriter.check(edits));

        assertTrue(outcome.written(), outcome.reason());
        String edited = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(countComments(edited) == countComments(original),
                "comments of the whole file have to survive an edit");
        List<String> before = original.lines().toList();
        List<String> after = edited.lines().toList();
        int start = entryLine(before, SECTION, firstEntry);
        int end = blockEndLine(before, start);
        assertEquals(before.subList(0, start), after.subList(0, start),
                "everything before the edited entry keeps its bytes");
        assertEquals(before.subList(end, before.size()),
                after.subList(after.size() - (before.size() - end), after.size()),
                "everything after the edited entry keeps its bytes");
        assertTrue(edited.contains("minecraft:apple"));
    }

    @Test
    void anEditKeepsSurroundingCommentsAndOrder() throws IOException {
        Path file = tmp.resolve("recipes.yml");
        String original = """
                # ==============================
                # operator header, must survive
                # ==============================
                cooking_pot_recipes:
                  # first recipe note
                  soup:            # inline note
                    result: minecraft:bowl
                  # last recipe note
                  stew:
                    result: minecraft:mushroom_stew
                """;
        Files.writeString(file, original);
        List<YamlFileTransaction.Edit> edits = List.of(new YamlFileTransaction.SetValue(
                List.of(SECTION, "soup"), Map.of("result", "minecraft:beetroot_soup")));

        YamlFileTransaction.Outcome outcome = YamlFileTransaction.apply(file, edits,
                BukkitYamlValueWriter.INSTANCE, BukkitYamlValueWriter.check(edits));

        assertTrue(outcome.written(), outcome.reason());
        String edited = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(edited.startsWith("# ==============================\n# operator header, must survive\n"));
        assertTrue(edited.contains("  # first recipe note\n  soup:\n"),
                "the comment above the edited entry stays where it was");
        assertTrue(edited.contains("  # last recipe note\n  stew:\n"),
                "an entry that was not edited keeps its comment and its key line");
        assertTrue(edited.contains("minecraft:beetroot_soup"));
        assertFalse(edited.contains("minecraft:bowl"), "the replaced body is gone");
    }

    @Test
    void aRefusedCheckLeavesTheFileByteIdentical() throws IOException {
        Path file = tmp.resolve("recipes.yml");
        byte[] original = copyOfRealRecipeFileBytes(file);
        List<YamlFileTransaction.Edit> edits = List.of(new YamlFileTransaction.SetValue(
                List.of(SECTION, "beef_stew"), Map.of("result", "minecraft:apple")));

        YamlFileTransaction.Outcome outcome = YamlFileTransaction.apply(file, edits,
                BukkitYamlValueWriter.INSTANCE, text -> "simulated refusal");

        assertFalse(outcome.written());
        assertEquals("simulated refusal", outcome.reason());
        assertArrayEqualsBytes(original, Files.readAllBytes(file));
    }

    @Test
    void aWriteFailureIsReportedAndChangesNothing() throws IOException {
        Path blocker = tmp.resolve("blocker");
        Files.writeString(blocker, "not a directory");
        Path file = blocker.resolve("recipes.yml");
        List<YamlFileTransaction.Edit> edits = List.of(new YamlFileTransaction.SetValue(
                List.of(SECTION, "soup"), Map.of("result", "minecraft:bowl")));

        YamlFileTransaction.Outcome outcome = YamlFileTransaction.apply(file, edits,
                BukkitYamlValueWriter.INSTANCE, null);

        assertFalse(outcome.written(), "a file that cannot be written has to be reported");
        assertNotNull(outcome.reason());
        assertEquals("not a directory", Files.readString(blocker), "nothing else was touched");
    }

    @Test
    void aFailureWhileReplacingCleansUpAndLeavesTheTargetAlone() throws IOException {
        Path target = tmp.resolve("recipes.yml");
        Files.createDirectory(target);
        Files.writeString(target.resolve("keep.txt"), "kept");
        List<YamlFileTransaction.Edit> edits = List.of(new YamlFileTransaction.SetValue(
                List.of(SECTION, "soup"), Map.of("result", "minecraft:bowl")));

        YamlFileTransaction.Outcome outcome = YamlFileTransaction.apply(target, edits,
                BukkitYamlValueWriter.INSTANCE, null);

        assertFalse(outcome.written());
        assertTrue(Files.isDirectory(target), "the target that could not be replaced is untouched");
        assertEquals("kept", Files.readString(target.resolve("keep.txt")));
        assertEquals(0, countTemporaryFiles(tmp), "a failed write leaves no temporary file behind");
    }

    @Test
    void concurrentEditsOfOneFileDoNotInterleave() throws Exception {
        Path file = tmp.resolve("recipes.yml");
        Files.writeString(file, SECTION + ":\n  seed:\n    result: minecraft:apple\n");
        int writers = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers);
        List<Thread> threads = new ArrayList<>();
        for (int index = 0; index < writers; index++) {
            int id = index;
            Thread thread = new Thread(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                    List<YamlFileTransaction.Edit> edits = List.of(new YamlFileTransaction.SetValue(
                            List.of(SECTION, "recipe_" + id), Map.of("result", "minecraft:stick")));
                    YamlFileTransaction.apply(file, edits, BukkitYamlValueWriter.INSTANCE,
                            BukkitYamlValueWriter.check(edits));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            threads.add(thread);
            thread.start();
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "every writer has to finish");
        for (Thread thread : threads) {
            thread.join();
        }

        String text = Files.readString(file, StandardCharsets.UTF_8);
        for (int index = 0; index < writers; index++) {
            assertTrue(text.contains("  recipe_" + index + ":"), "writer " + index + " lost its entry");
        }
        assertEquals(1, text.lines().filter(line -> line.startsWith(SECTION + ":")).count(),
                "the file still holds exactly one section");
        assertTrue(text.contains("  seed:"), "the entry that was there before is still there");
    }

    @Test
    void repeatingAnEditWritesNothingTheSecondTime() throws IOException {
        Path file = tmp.resolve("recipes.yml");
        Files.writeString(file, SECTION + ":\n  soup:\n    result: minecraft:bowl\n");
        List<YamlFileTransaction.Edit> edits = List.of(new YamlFileTransaction.SetValue(
                List.of(SECTION, "soup"), Map.of("result", "minecraft:beetroot_soup")));

        assertTrue(YamlFileTransaction.apply(file, edits, BukkitYamlValueWriter.INSTANCE,
                BukkitYamlValueWriter.check(edits)).written());
        String once = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(YamlFileTransaction.apply(file, edits, BukkitYamlValueWriter.INSTANCE,
                BukkitYamlValueWriter.check(edits)).written());
        assertEquals(once, Files.readString(file, StandardCharsets.UTF_8),
                "applying the same edit again changes nothing");
    }

    @Test
    void aFileTheLocatorCannotFollowIsRefused() throws IOException {
        Path flow = tmp.resolve("flow.yml");
        String original = SECTION + ": {soup: {result: minecraft:bowl}}\n";
        Files.writeString(flow, original);
        List<YamlFileTransaction.Edit> edits = List.of(new YamlFileTransaction.SetValue(
                List.of(SECTION, "soup"), Map.of("result", "minecraft:apple")));

        YamlFileTransaction.Outcome outcome = YamlFileTransaction.apply(flow, edits,
                BukkitYamlValueWriter.INSTANCE, null);

        assertFalse(outcome.written(), "a flow-style section is refused instead of guessed at");
        assertEquals(original, Files.readString(flow, StandardCharsets.UTF_8));

        Path scalar = tmp.resolve("scalar.yml");
        String block = SECTION + ":\n  soup:\n    note: |\n      multi\n      line\n";
        Files.writeString(scalar, block);
        YamlFileTransaction.Outcome refused = YamlFileTransaction.apply(scalar,
                List.of(new YamlFileTransaction.SetValue(List.of(SECTION, "soup"), Map.of("result", "x"))),
                BukkitYamlValueWriter.INSTANCE, null);
        assertFalse(refused.written(), "a block scalar is refused instead of guessed at");
        assertEquals(block, Files.readString(scalar, StandardCharsets.UTF_8));
    }

    @Test
    void aMissingSectionAndEntryAreCreated() throws IOException {
        Path file = tmp.resolve("recipes.yml");
        Files.writeString(file, SECTION + ":\n  soup:\n    result: minecraft:bowl\n");
        List<YamlFileTransaction.Edit> edits = List.of(
                new YamlFileTransaction.SetValue(List.of("external-overrides", "cooking_pot"),
                        List.of("soup", "stew")),
                new YamlFileTransaction.SetValue(List.of(SECTION, "stew"), Map.of("result", "minecraft:stew")));

        YamlFileTransaction.Outcome outcome = YamlFileTransaction.apply(file, edits,
                BukkitYamlValueWriter.INSTANCE, BukkitYamlValueWriter.check(edits));

        assertTrue(outcome.written(), outcome.reason());
        String edited = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(edited.contains("external-overrides:"), "a missing section is created");
        assertTrue(edited.contains("cooking_pot:"), "the entry is created under it");
        YamlConfiguration parsed = new YamlConfiguration();
        try {
            parsed.loadFromString(edited);
        } catch (InvalidConfigurationException broken) {
            throw new AssertionError(broken);
        }
        assertEquals(List.of("soup", "stew"), parsed.getStringList("external-overrides.cooking_pot"));
        assertTrue(edited.contains("  stew:"));
    }

    @Test
    void removingAnEntryDropsOnlyItsOwnComment() throws IOException {
        Path own = tmp.resolve("own.yml");
        Files.writeString(own, "root:\n  # note for the only entry\n  only:\n    a: 1\n");
        assertTrue(YamlFileTransaction.apply(own,
                List.of(new YamlFileTransaction.RemoveValue(List.of("root", "only"))),
                BukkitYamlValueWriter.INSTANCE, null).written());
        assertFalse(Files.readString(own).contains("note for the only entry"),
                "a comment that belongs to the removed entry goes with it");

        Path shared = tmp.resolve("shared.yml");
        String grouped = "root:\n  first:\n    a: 1\n  # separator before the second entry\n  second:\n    b: 2\n";
        Files.writeString(shared, grouped);
        assertTrue(YamlFileTransaction.apply(shared,
                List.of(new YamlFileTransaction.RemoveValue(List.of("root", "second"))),
                BukkitYamlValueWriter.INSTANCE, null).written());
        assertTrue(Files.readString(shared).contains("separator before the second entry"),
                "a comment shared with the section is kept");
    }

    @Test
    void theWriterBuildsTheBlockAtTheRequestedDepth() {
        String block = BukkitYamlValueWriter.INSTANCE.block("soup", Map.of("result", "minecraft:bowl"), 2);
        assertEquals("  soup:\n    result: minecraft:bowl\n", block);

        String topLevel = BukkitYamlValueWriter.INSTANCE.block("external-overrides", List.of("soup"), 0);
        YamlConfiguration parsed = new YamlConfiguration();
        try {
            parsed.loadFromString(topLevel);
        } catch (InvalidConfigurationException broken) {
            throw new AssertionError("the top-level block has to be a complete document: " + broken);
        }
        assertEquals(List.of("soup"), parsed.getStringList("external-overrides"));
    }

    @Test
    void theCheckRefusesAValueThatDidNotLand() {
        List<YamlFileTransaction.Edit> edits = List.of(new YamlFileTransaction.SetValue(
                List.of("root", "key"), "wanted"));

        assertNotNull(BukkitYamlValueWriter.check(edits).problem("root:\n  key: other\n"));
        assertEquals(null, BukkitYamlValueWriter.check(edits).problem("root:\n  key: wanted\n"));
    }

    // ---------------------------------------------------------------- helpers

    private Path copyOfRealRecipeFile() throws IOException {
        Path file = tmp.resolve("cooking_pot_recipes.yml");
        copyOfRealRecipeFileBytes(file);
        return file;
    }

    private byte[] copyOfRealRecipeFileBytes(Path file) throws IOException {
        Path source = locate("recipes/cooking_pot_recipes.yml");
        assertNotNull(source, "the shipped recipe file has to be reachable");
        byte[] bytes = Files.readAllBytes(source);
        Files.write(file, bytes);
        return bytes;
    }

    private static String firstEntryAfterRoot(String text) {
        YamlConfiguration parsed = new YamlConfiguration();
        try {
            parsed.loadFromString(text);
        } catch (InvalidConfigurationException broken) {
            throw new AssertionError(broken);
        }
        ConfigurationSection section = parsed.getConfigurationSection(SECTION);
        assertNotNull(section, "the shipped file has a " + SECTION + " section");
        String first = section.getKeys(false).iterator().next();
        return first;
    }

    /** The character offset of the line that opens one entry of a section. */
    private static int entryLine(List<String> lines, String section, String id) {
        boolean inSection = false;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.startsWith(section + ":")) {
                inSection = true;
            } else if (inSection && line.equals("  " + id + ":")) {
                return index;
            } else if (inSection && !line.startsWith(" ") && !line.isBlank()) {
                break;
            }
        }
        throw new AssertionError("entry " + id + " not found in " + section);
    }

    /** The first line after an entry's block: the next mapping key at or above the entry's indentation. */
    private static int blockEndLine(List<String> lines, int start) {
        for (int index = start + 1; index < lines.size(); index++) {
            String line = lines.get(index);
            String stripped = line.strip();
            if (stripped.isEmpty() || stripped.startsWith("#") || stripped.startsWith("-")
                    || !stripped.contains(":")) {
                continue;
            }
            int indent = line.length() - line.stripLeading().length();
            if (indent <= 2) {
                return index;
            }
        }
        return lines.size();
    }

    private static int countComments(String text) {
        return (int) text.lines().filter(line -> line.stripLeading().startsWith("#")).count();
    }

    private static long countTemporaryFiles(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            return entries.filter(path -> path.getFileName().toString().endsWith(".tmp")).count();
        }
    }

    private static void assertArrayEqualsBytes(byte[] expected, byte[] actual) {
        assertTrue(MessageDigest.isEqual(expected, actual),
                "the file has to stay byte for byte as it was");
    }

    private static Path locate(String relative) {
        String[] prefixes = {"src/main/resources/", "FarmersDelight/src/main/resources/"};
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
