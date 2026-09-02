package com.huidu.farmersdelight.util;

import org.bukkit.Bukkit;
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

    public static boolean sameNormalizedPath(Path first, Path second) {
        return first.toAbsolutePath().normalize().equals(second.toAbsolutePath().normalize());
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

    // The datapack pack_format is version-specific: single value up to 1.21.8, a min_format/max_format
    // range from 1.21.9 onward. Generating it from the running server avoids the "incompatible pack"
    // warning and keeps the installers working across the whole 1.21.4~26.x range.
    public static String renderPackMetadata(String description) {
        return renderPackMetadata(description, Bukkit.getBukkitVersion());
    }

    // Exposed with an explicit version so installers can be unit-tested without a live server.
    public static String renderPackMetadata(String description, String version) {
        PackFormat format = packFormatFor(version);
        String line = format.range
                ? "    \"min_format\": " + format.value + ",\n    \"max_format\": " + MAX_RANGE_FORMAT
                : "    \"pack_format\": " + format.value;
        return "{\n  \"pack\": {\n" + line + ",\n    \"description\": \"" + description + "\"\n  }\n}\n";
    }

    private static final int MAX_RANGE_FORMAT = 150;

    private static final PackFormat PACK_FORMAT_1_21_4 = new PackFormat(61, false);
    private static final PackFormat PACK_FORMAT_1_21_5 = new PackFormat(71, false);
    private static final PackFormat PACK_FORMAT_1_21_6_8 = new PackFormat(80, false);
    private static final PackFormat PACK_FORMAT_RANGE = new PackFormat(88, true);

    // Data pack format history: 1.21.4=61, 1.21.5=71, 1.21.6/7/8=80; 1.21.9 and every 26.x release use
    // the min_format/max_format range form (88 is the 1.21.9 data pack major format).
    private static PackFormat packFormatFor(String version) {
        if (version.contains("1.21.4")) {
            return PACK_FORMAT_1_21_4;
        }
        if (version.contains("1.21.5")) {
            return PACK_FORMAT_1_21_5;
        }
        if (version.contains("1.21.6") || version.contains("1.21.7") || version.contains("1.21.8")) {
            return PACK_FORMAT_1_21_6_8;
        }
        return PACK_FORMAT_RANGE;
    }

    private record PackFormat(int value, boolean range) {
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
