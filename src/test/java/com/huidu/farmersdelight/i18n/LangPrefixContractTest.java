package com.huidu.farmersdelight.i18n;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The brand prefix lives in exactly one place and every message asks for it there.
 *
 * The language files are read as files: the rule is about what the two shipped languages contain, so it can
 * be checked without a server, and a new language file that copies the markup is caught the moment it lands.
 * The injection itself is a rule about the lookup path, so it is pinned on the source text instead.
 */
class LangPrefixContractTest {

    private static final Path LANG_DIR = Path.of("src", "main", "resources", "lang");
    private static final Path I18N = Path.of("src", "main", "java", "com", "huidu", "farmersdelight", "i18n",
            "I18n.java");
    private static final String BRAND_MARKUP = "<gradient:gold:yellow><b>FarmersDelight</b>";
    private static final String PREFIX_KEY = "general.prefix";
    private static final String PREFIX_PLACEHOLDER = "{prefix}";
    /** A1 is settled: every arrow-carrying message names the brand through the prefix placeholder. */
    private static final Set<String> ARROW_WITHOUT_BRAND = Set.of();

    private static Map<String, String> leaves(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            Object loaded = new Yaml().load(input);
            Map<String, String> flat = new LinkedHashMap<>();
            flatten("", loaded, flat);
            return flat;
        }
    }

    private static void flatten(String trail, Object node, Map<String, String> out) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = trail.isEmpty() ? String.valueOf(entry.getKey()) : trail + "." + entry.getKey();
                flatten(key, entry.getValue(), out);
            }
        } else if (node != null) {
            out.put(trail, String.valueOf(node));
        }
    }

    private static String flat(Path path) throws IOException {
        return Files.readString(path).replaceAll("\\s+", " ");
    }

    @Test
    void bothLanguagesCarryTheSameKeys() throws IOException {
        Map<String, String> en = leaves(LANG_DIR.resolve("en_us.yml"));
        Map<String, String> zh = leaves(LANG_DIR.resolve("zh_cn.yml"));
        assertFalse(en.isEmpty(), "the English file has to load");
        assertEquals(en.keySet(), zh.keySet(), "every language file has to offer the same keys");
    }

    @Test
    void noMessageCopiesTheBrandMarkupAndTheDeadPrefixIsGone() throws IOException {
        for (String name : List.of("en_us.yml", "zh_cn.yml")) {
            Map<String, String> values = leaves(LANG_DIR.resolve(name));
            List<String> copied = new ArrayList<>();
            List<String> arrows = new ArrayList<>();
            for (Map.Entry<String, String> entry : values.entrySet()) {
                if (entry.getValue().contains(BRAND_MARKUP) && !entry.getKey().equals(PREFIX_KEY)) {
                    copied.add(entry.getKey());
                }
                if (entry.getValue().contains("\u00bb") && !entry.getKey().equals(PREFIX_KEY)) {
                    arrows.add(entry.getKey());
                }
            }
            assertTrue(copied.isEmpty(), name + " copies the brand markup instead of using "
                    + PREFIX_PLACEHOLDER + ": " + copied);
            // A1 is settled, so no value may carry an arrow of its own any more.
            assertEquals(new HashSet<>(ARROW_WITHOUT_BRAND), new HashSet<>(arrows),
                    name + ": every arrow has to come from the prefix placeholder");
            assertFalse(values.containsKey("console.prefix"),
                    name + " still defines the unused console prefix key");
            assertTrue(values.getOrDefault(PREFIX_KEY, "").contains("FarmersDelight"),
                    name + " has to define the brand prefix");
            assertTrue(values.containsKey("command.debug_tools_failed"),
                    name + " needs the language key the debug-tools failure path now uses");
        }
    }

    @Test
    void everyPlaceholderValueGoesThroughTheInjectingLookup() throws IOException {
        String source = flat(I18N);
        assertTrue(source.contains("private static String applyPrefix(String key, String value, String locale)"),
                "the lookup has to resolve the brand prefix itself");
        assertEquals(3, count(source, "return applyPrefix(key, value, locale);")
                        + count(source, "return applyPrefix(key, bundled, locale);"),
                "every value the lookup can return has to pass through the injection");
        assertTrue(source.contains("key.equals(PREFIX_KEY)"),
                "the prefix key itself must never be injected into, or the lookup would recurse");
        assertTrue(source.contains("value.contains(PREFIX_PLACEHOLDER)"),
                "a value without the placeholder has to be returned untouched");
        // Both shipped languages resolve the placeholder, so no call site has to pass it by hand.
        for (String name : List.of("en_us.yml", "zh_cn.yml")) {
            Map<String, String> values = leaves(LANG_DIR.resolve(name));
            assertTrue(values.get("general.config_reloaded").contains(PREFIX_PLACEHOLDER),
                    name + ": the reload receipt asks for the prefix through the placeholder");
            assertTrue(values.get("command.help_title").contains(PREFIX_PLACEHOLDER),
                    name + ": the help title asks for the prefix through the placeholder");
        }
    }

    private static int count(String text, String needle) {
        int total = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            total++;
            index = text.indexOf(needle, index + needle.length());
        }
        return total;
    }
}
