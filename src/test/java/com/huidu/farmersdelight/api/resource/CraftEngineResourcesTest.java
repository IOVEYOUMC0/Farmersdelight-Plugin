package com.huidu.farmersdelight.api.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CraftEngineResourcesTest {

    @TempDir
    Path directory;

    @Test
    void installsMissingFilesWithoutOverwritingOrEscapingTheNamespace() throws IOException {
        Path jar = createJar();
        Path plugins = directory.resolve("plugins");

        assertEquals(1, CraftEngineResources.release(jar, plugins, "demo", true));
        Path target = plugins.resolve("CraftEngine/resources/demo/nested/file.txt");
        assertEquals("bundled", Files.readString(target));
        assertFalse(Files.exists(plugins.resolve("CraftEngine/resources/escape.txt")));

        Files.writeString(target, "edited");
        assertEquals(0, CraftEngineResources.release(jar, plugins, "demo", true));
        assertEquals("edited", Files.readString(target));
        assertEquals(0, CraftEngineResources.release(jar, plugins, "demo", false));
    }

    @Test
    void rejectsUnsafeNamespaces() {
        assertThrows(IllegalArgumentException.class,
                () -> CraftEngineResources.release(directory.resolve("missing.jar"), directory, "../outside", true));
    }

    @Test
    void migratesLegacyPositionArgumentsWithoutCompletingExistingResources() throws IOException {
        Path jar = createJar();
        Path target = directory.resolve("plugins/CraftEngine/resources/demo/config.yml");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "x: <arg:block.block_x>\ny: <arg:block.block_y>\nz: <arg:block.block_z>\n");

        assertEquals(1, CraftEngineResources.release(jar, directory.resolve("plugins"), "demo", false));
        assertEquals("x: <arg:position.block_x>\ny: <arg:position.block_y>\nz: <arg:position.block_z>\n",
                Files.readString(target));
    }

    private Path createJar() throws IOException {
        Path jar = directory.resolve("addon.jar");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(jar))) {
            add(output, "craftengine/demo/nested/file.txt", "bundled");
            add(output, "craftengine/demo/../escape.txt", "escaped");
            add(output, "craftengine/other/ignored.txt", "ignored");
        }
        return jar;
    }

    private static void add(ZipOutputStream output, String name, String content) throws IOException {
        output.putNextEntry(new ZipEntry(name));
        output.write(content.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }
}
