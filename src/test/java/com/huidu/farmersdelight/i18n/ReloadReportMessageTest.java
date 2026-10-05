package com.huidu.farmersdelight.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reported reload line has to keep its placeholders and lose the dangling separator.
 *
 *
 * The live defect: a targeted reload showed "(0ms: )" because the literal colon lived in the language string
 * while the phase list it belonged to was empty. The colon now travels with the phases, so both halves are
 * pinned here: no literal separator in the string, and exactly the placeholders the command supplies.
 */
class ReloadReportMessageTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

    @Test
    void neitherLanguageLeavesADanglingSeparatorBeforeThePhaseList() throws IOException {
        for (String file : new String[]{"en_us.yml", "zh_cn.yml"}) {
            String value = value(langFile(file), "reload_report");
            assertFalse(value.matches("(?s).*[:：]\\s*\\{split}.*"),
                    file + " must not put a literal colon in front of {split}: " + value);
            assertTrue(value.contains("{total}"), file + " has to report the elapsed time");
            assertTrue(value.contains("{split}"), file + " has to report the phase list");
        }
    }

    @Test
    void theCommandSuppliesExactlyThePlaceholdersTheMessageUses() throws IOException {
        Set<String> required = new LinkedHashSet<>();
        for (String file : new String[]{"en_us.yml", "zh_cn.yml"}) {
            Matcher matcher = PLACEHOLDER.matcher(value(langFile(file), "reload_report"));
            while (matcher.find()) {
                required.add(matcher.group(1));
            }
        }
        assertEquals(Set.of("prefix", "total", "split"), required,
                "the command sends prefix, total and split; any other name would print literally");
    }

    private static String langFile(String name) throws IOException {
        String path = "/lang/" + name;
        try (InputStream in = ReloadReportMessageTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "language file " + path + " has to be on the test classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * The command's own definition: the language file declares the same key once for the legacy plain-text
     * channel and once for the component channel the command actually renders, so the one carrying {prefix} is
     * the one to pin.
     */
    private static String value(String yaml, String key) {
        String found = null;
        for (String line : yaml.split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(key + ":")) {
                String candidate = trimmed.substring(key.length() + 1).trim();
                if (found == null || candidate.contains("{prefix}")) {
                    found = candidate;
                }
            }
        }
        return found;
    }
}
