package com.huidu.farmersdelight.pack;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four pie slices are items, so their display name has to live on an {@code item.farmersdelight.*} key in
 * both carriers (the client resource-pack language files and the CraftEngine pack translations). The block
 * prefix would work by accident but splits the same name over two key sets, so this test pins the item prefix
 * and the absence of any {@code block.*} leftovers.
 */
class PieSliceNameKeysTest {

    private static final List<String> SLICES = List.of(
            "apple_pie_slice", "chocolate_pie_slice", "sweet_berry_cheesecake_slice", "pumpkin_pie_slice");

    private static final List<String> CE_SECTIONS = List.of("en", "zh_cn", "ko_kr");

    private static final Path LANG_DIR = Path.of("src", "main", "resources", "craftengine", "farmersdelight",
            "resourcepack", "assets", "farmersdelight", "lang");

    @Test
    void everyPieSliceNameKeyUsesTheItemPrefixInItemsYml() throws IOException {
        String items = read("craftengine/farmersdelight/configuration/items.yml");
        for (String slice : SLICES) {
            assertTrue(items.contains("item-name: <!i><lang:item.farmersdelight." + slice + ">"),
                    slice + " has to resolve its name through item.farmersdelight." + slice);
            assertFalse(items.contains("block.farmersdelight." + slice + ">"),
                    slice + " must not keep the block prefix in items.yml");
        }
    }

    @Test
    void theCraftEngineTranslationsDefineExactlyOneItemKeyPerSlice() throws IOException {
        String yaml = read("craftengine/farmersdelight/configuration/translations.yml");
        String section = null;
        int[] item = new int[SLICES.size()];
        int[] block = new int[SLICES.size()];
        for (String line : yaml.split("\n")) {
            if (line.matches("^ {2}[a-z_]+:$")) {
                section = line.trim();
                continue;
            }
            if (section == null) {
                continue;
            }
            for (int i = 0; i < SLICES.size(); i++) {
                if (line.startsWith("    item.farmersdelight." + SLICES.get(i) + ": \"")) {
                    item[i]++;
                }
                if (line.startsWith("    block.farmersdelight." + SLICES.get(i) + ": \"")) {
                    block[i]++;
                }
            }
        }
        for (int i = 0; i < SLICES.size(); i++) {
            assertEquals(CE_SECTIONS.size(), item[i],
                    "the CE translations need one item key per language for " + SLICES.get(i));
            assertEquals(0, block[i],
                    "no block-prefixed key may survive for " + SLICES.get(i));
        }
    }

    @Test
    void everyLocaleDefinesTheItemKeyOnceAndNoBlockLeftover() throws IOException {
        assertTrue(Files.isDirectory(LANG_DIR), "missing language directory " + LANG_DIR);
        List<String> files = new ArrayList<>();
        try (Stream<Path> paths = Files.list(LANG_DIR)) {
            paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(path -> files.add(path.getFileName().toString()));
        }
        assertFalse(files.isEmpty(), "no locale files found");
        for (String file : files) {
            String text = Files.readString(LANG_DIR.resolve(file), StandardCharsets.UTF_8);
            for (String slice : SLICES) {
                assertEquals(1, count(text, "\"item.farmersdelight." + slice + "\":"),
                        file + " needs exactly one item key for " + slice);
                assertEquals(0, count(text, "\"block.farmersdelight." + slice + "\":"),
                        file + " must not keep a block-prefixed " + slice + " key");
            }
        }
    }

    private static int count(String text, String needle) {
        int hits = 0;
        int at = text.indexOf(needle);
        while (at >= 0) {
            hits++;
            at = text.indexOf(needle, at + 1);
        }
        return hits;
    }

    private static String read(String resource) throws IOException {
        try (InputStream stream = PieSliceNameKeysTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream, "missing classpath resource " + resource);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
