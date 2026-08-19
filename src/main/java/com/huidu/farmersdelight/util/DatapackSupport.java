package com.huidu.farmersdelight.util;

import org.bukkit.World;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

// Shared helpers for the datapack installers (loot + damage type). Each installer writes its own
// world datapack folder from the bundled resources; these methods keep the IO/idempotence logic in
// one place so the installers stay thin.
public final class DatapackSupport {

    private DatapackSupport() {
    }

    // Resolves the world folder that holds level.dat, i.e. the folder whose datapacks/ directory the
    // vanilla pack repository scans.
    public static Path worldRoot(World world) {
        Path folder = world.getWorldFolder().toPath();
        while (folder != null && !Files.exists(folder.resolve("level.dat"))) {
            folder = folder.getParent();
        }
        return folder != null ? folder : world.getWorldFolder().toPath();
    }

    public static JarFile openPluginJar(Class<?> pluginClass) throws IOException {
        URL location = pluginClass.getProtectionDomain().getCodeSource().getLocation();
        try {
            return new JarFile(Paths.get(location.toURI()).toFile());
        } catch (URISyntaxException e) {
            throw new IOException("Could not resolve plugin jar location: " + location, e);
        }
    }

    public static String readJarEntry(JarFile jar, String name) {
        JarEntry entry = jar.getJarEntry(name);
        if (entry == null) {
            return null;
        }
        try (InputStream in = jar.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    public static boolean fileMatches(Path dest, String content) {
        if (!Files.isRegularFile(dest)) {
            return false;
        }
        try {
            return Files.readString(dest, StandardCharsets.UTF_8).equals(content);
        } catch (IOException e) {
            return false;
        }
    }

    // Writes content only when it differs from what is on disk; returns true when a write happened.
    public static boolean writeIfChanged(Path dest, String content) {
        if (fileMatches(dest, content)) {
            return false;
        }
        try {
            Path parent = dest.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(dest, content, StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write " + dest, e);
        }
    }

    public static void copyFromJar(JarFile jar, JarEntry entry, Path dest) throws IOException {
        Path parent = dest.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (InputStream in = jar.getInputStream(entry)) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
