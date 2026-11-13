package com.huidu.farmersdelight.manager;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stove and skillet dispatch loops run as a Folia global repeating task. Bukkit.getCurrentTick() throws
 * "No currently ticking region" there, so those loops must use a monotonic clock that never asks the server for
 * the current region tick.
 */
class FoliaTickSourceContractTest {

    @Test
    void stoveDispatchUsesAMonotonicClock() throws IOException {
        assertDispatchHasNoBukkitTick("src/main/java/com/huidu/farmersdelight/manager/StoveManager.java",
                "    private void scheduleStoveTick(");
    }

    @Test
    void skilletDispatchUsesAMonotonicClock() throws IOException {
        assertDispatchHasNoBukkitTick("src/main/java/com/huidu/farmersdelight/manager/SkilletManager.java",
                "    private void scheduleSkilletTick(");
    }

    private static void assertDispatchHasNoBukkitTick(String relative, String methodStart) throws IOException {
        String source = read(relative);
        int start = source.indexOf(methodStart);
        assertTrue(start > 0, relative + " has to keep the dispatch method");
        int next = source.indexOf("\n    private ", start + methodStart.length());
        String body = next > 0 ? source.substring(start, next) : source.substring(start);
        StringBuilder code = new StringBuilder();
        for (String line : body.split("\n")) {
            if (!line.trim().startsWith("//")) {
                code.append(line).append('\n');
            }
        }
        assertFalse(code.toString().contains("Bukkit.getCurrentTick()"),
                relative + " dispatch task has no ticking region and must not call Bukkit.getCurrentTick()");
        assertTrue(code.toString().contains("System.nanoTime()"),
                relative + " dispatch has to use a monotonic clock");
    }

    private static String read(String relative) throws IOException {
        for (String prefix : new String[]{"", "FarmersDelight/"}) {
            Path path = Path.of(prefix + relative);
            if (Files.isRegularFile(path)) return Files.readString(path);
        }
        throw new IOException("source not found: " + relative);
    }
}
