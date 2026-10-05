package com.huidu.farmersdelight.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The report cap: a long report is cut to the limit while the hidden count is kept, and nothing changes when the
 * report fits or the limit is off. The last test holds the debug command to that cap, because its validation
 * report follows the loaded recipe count and would otherwise send one line per recipe with no bound at all.
 */
class OutputCapTest {

    private static final Path DEBUG_COMMAND = Path.of("src", "debugTools", "java", "com", "huidu",
            "farmersdelight", "debug", "DebugToolsCommand.java");

    @Test
    void aReportLongerThanTheLimitIsCutToTheLimit() {
        List<String> lines = List.of("a", "b", "c", "d", "e");
        List<String> capped = OutputCap.cap(lines, 3);
        assertEquals(3, capped.size());
        assertEquals(List.of("a", "b", "c"), capped);
    }

    @Test
    void aReportThatFitsOrALimitThatIsOffIsHandedBackUnchanged() {
        List<String> lines = List.of("a", "b");
        assertSame(lines, OutputCap.cap(lines, 3), "a report that fits is not copied");
        assertSame(lines, OutputCap.cap(lines, 2), "the limit itself keeps the whole report");
        assertSame(lines, OutputCap.cap(lines, 0), "a zero limit means no bound");
        assertSame(lines, OutputCap.cap(lines, -4), "a negative limit means no bound");
    }

    @Test
    void theHiddenCountIsThePartOfTheReportThatWasDropped() {
        assertEquals(7, OutputCap.overflow(10, 3));
        assertEquals(0, OutputCap.overflow(3, 3));
        assertEquals(0, OutputCap.overflow(2, 3));
        assertEquals(0, OutputCap.overflow(10, 0), "no limit hides nothing");
        assertEquals(0, OutputCap.overflow(10, -1), "a negative limit hides nothing");
    }

    @Test
    void whatIsShownPlusWhatIsHiddenIsAlwaysTheWholeReport() {
        List<String> lines = List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j");
        for (int cap = 1; cap <= 12; cap++) {
            assertEquals(lines.size(), OutputCap.cap(lines, cap).size() + OutputCap.overflow(lines.size(), cap),
                    "the shown lines and the hidden count must add up to the report size at limit " + cap);
        }
    }

    @Test
    void theDebugCommandSendsItsUnboundedReportThroughTheCap() throws IOException {
        String source = read(DEBUG_COMMAND);
        String validation = methodBody(source, "private void recipeValidate(Player player)");
        assertTrue(validation.contains("batches.start(player,"),
                "recipe validation must run through the batch runner instead of one whole-list pass");
        assertTrue(validation.contains("sendCapped(player,"),
                "the validation report must be sent through the cap");
        assertFalse(validation.contains("for (String line : issues)"),
                "the validation report must not be sent line by line with no bound");
        String capped = methodBody(source, "private void sendCapped(Player player,");
        assertTrue(capped.contains("OutputCap.cap("), "the capped send must apply OutputCap.cap");
        assertTrue(capped.contains("OutputCap.overflow("), "the capped send must report the hidden count");
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing method: " + signature);
        int next = source.indexOf("\n    private ", start + signature.length());
        return next < 0 ? source.substring(start) : source.substring(start, next);
    }

    private static String read(Path path) throws IOException {
        Path resolved = path;
        if (!Files.isRegularFile(resolved)) {
            Path nested = Path.of("FarmersDelight").resolve(path);
            if (Files.isRegularFile(nested)) {
                resolved = nested;
            }
        }
        assertTrue(Files.isRegularFile(resolved), "missing source file: " + path + " (working directory "
                + Path.of("").toAbsolutePath() + ")");
        return Files.readString(resolved).replace("\r\n", "\n");
    }
}
