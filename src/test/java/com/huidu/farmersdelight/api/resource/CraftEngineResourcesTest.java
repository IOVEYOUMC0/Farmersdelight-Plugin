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
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void leavesALegacyPositionArgumentAloneInAnExistingNamespace() throws IOException {
        Path jar = createJar();
        Path target = directory.resolve("plugins/CraftEngine/resources/demo/config.yml");
        Files.createDirectories(target.getParent());
        String legacy = "x: <arg:block.block_x>\ny: <arg:block.block_y>\nz: <arg:block.block_z>\n";
        Files.writeString(target, legacy);

        // The one-off rewrite is retired: an existing namespace is left exactly as the operator has it, and no
        // migration marker is written next to it.
        assertEquals(0, CraftEngineResources.release(jar, directory.resolve("plugins"), "demo", false));
        assertEquals(legacy, Files.readString(target));
        assertFalse(Files.exists(target.getParent().resolve(".position-args-migrated")),
                "the marker the retired migration wrote is gone");
    }

    @Test
    void noSourceRewritesTheLegacyPositionArgumentsAnymore() throws IOException {
        for (String relative : new String[] {
                "src/main/java/com/huidu/farmersdelight/api/resource/CraftEngineResources.java",
                "src/main/java/com/huidu/farmersdelight/resource/ResourceInstaller.java"}) {
            Path source = Path.of(relative);
            if (!Files.isRegularFile(source)) {
                source = Path.of("FarmersDelight").resolve(relative);
            }
            String text = Files.readString(source);

            assertFalse(text.contains("position-args-migrated"),
                    relative + " still knows the retired migration marker");
            assertFalse(text.contains("migrateLegacyPositionArguments"),
                    relative + " still carries the retired migration");
            assertFalse(text.contains("<arg:block.block_"),
                    relative + " still rewrites the legacy position arguments");
        }
    }

    @Test
    void neverReplacesInstalledFilesEvenWhenTheBundledVersionIsHigher() throws IOException {
        Path plugins = directory.resolve("plugins");
        Path installed = plugins.resolve("CraftEngine/resources/demo");
        Files.createDirectories(installed.resolve("nested"));
        Files.writeString(installed.resolve("pack.yml"), "version: 0.0.1\n");
        Files.writeString(installed.resolve("nested/file.txt"), "edited");
        Files.writeString(installed.resolve("edited.txt"), "operator edit");

        Path jar = createVersionedJar("0.0.2");
        assertEquals(0, CraftEngineResources.release(jar, plugins, "demo", true));
        assertEquals("version: 0.0.1\n", Files.readString(installed.resolve("pack.yml")));
        assertEquals("edited", Files.readString(installed.resolve("nested/file.txt")));
        assertEquals("operator edit", Files.readString(installed.resolve("edited.txt")));
    }

    @Test
    void installsOnlyMissingFilesIntoAnExistingNamespace() throws IOException {
        Path plugins = directory.resolve("plugins");
        Path installed = plugins.resolve("CraftEngine/resources/demo");
        Files.createDirectories(installed.resolve("nested"));
        Files.writeString(installed.resolve("pack.yml"), "version: 9.9\n");
        Files.writeString(installed.resolve("nested/file.txt"), "old");
        Files.writeString(installed.resolve("added.txt"), "operator file");

        Path jar = createVersionedJar("0.0.2");
        assertEquals(1, CraftEngineResources.release(jar, plugins, "demo", true));
        assertEquals("old", Files.readString(installed.resolve("nested/file.txt")));
        assertEquals("operator file", Files.readString(installed.resolve("added.txt")));
        assertEquals("version: 9.9\n", Files.readString(installed.resolve("pack.yml")));
        assertEquals("bundled", Files.readString(installed.resolve("edited.txt")));
    }

    private Path createVersionedJar(String version) throws IOException {
        Path jar = directory.resolve("addon-" + version + ".jar");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(jar))) {
            add(output, "craftengine/demo/pack.yml", "author: test\nversion: " + version + "\n");
            add(output, "craftengine/demo/nested/file.txt", "bundled");
            add(output, "craftengine/demo/edited.txt", "bundled");
        }
        return jar;
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
