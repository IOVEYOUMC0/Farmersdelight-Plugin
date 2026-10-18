package com.huidu.farmersdelight.api.util;

import io.papermc.paper.datapack.Datapack;
import io.papermc.paper.datapack.DatapackRegistrar;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.World;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.Objects;
import java.util.List;
import java.util.Comparator;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

// Shared file and discovery helpers; registry content loads before plugin enable.
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
    // warning and keeps the installers working across the whole 1.21.5~26.x range.
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

    private static final PackFormat PACK_FORMAT_1_21_5 = new PackFormat(71, false);
    private static final PackFormat PACK_FORMAT_1_21_6 = new PackFormat(80, false);
    private static final PackFormat PACK_FORMAT_1_21_7_8 = new PackFormat(81, false);
    private static final PackFormat PACK_FORMAT_RANGE = new PackFormat(88, true);

    // Releases before 1.21.9 require an exact format; later releases accept the declared range.
    private static PackFormat packFormatFor(String version) {
        if (version.contains("1.21.5")) {
            return PACK_FORMAT_1_21_5;
        }
        if (version.startsWith("1.21.6")) {
            return PACK_FORMAT_1_21_6;
        }
        if (version.startsWith("1.21.7") || version.startsWith("1.21.8")) {
            return PACK_FORMAT_1_21_7_8;
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
            Path parent = dest.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporary = Files.createTempFile(parent, dest.getFileName().toString(), ".tmp");
            try {
                Files.writeString(temporary, content, StandardCharsets.UTF_8);
                try {
                    Files.move(temporary, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
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


    public static Path managedPack(Path dataDirectory, String name) {
        return dataDirectory.resolve("datapacks").resolve(name);
    }

    public static YamlConfiguration startupConfiguration(Path file, InputStream defaults)
            throws IOException, InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        try (InputStream bundled = defaults) {
            if (Files.exists(file)) {
                config.load(file.toFile());
            } else {
                config.load(new InputStreamReader(Objects.requireNonNull(bundled), StandardCharsets.UTF_8));
            }
        }
        return config;
    }

    public static void discoverPack(DatapackRegistrar registrar, Path dataDirectory, String name,
                                    boolean enabled) throws IOException {
        Path pack = managedPack(dataDirectory, name);
        if (!enabled || Files.exists(pack.resolve(".legacy-migrated"))) {
            registrar.removeDiscoveredPack("file/" + name);
        }
        if (enabled && !Files.exists(pack.resolve(".legacy-migrated"))
                && registrar.hasPackDiscovered("file/" + name)) {
            return; // Preserve the legacy definitions during the migration boot.
        }
        if (enabled && Files.isRegularFile(pack.resolve("pack.mcmeta"))) {
            // Required packs compare Adventure titles before registry initialization on Paper 26.2.
            if (registrar.discoverPack(pack, name,
                    options -> options.position(false, Datapack.Position.TOP)) == null) {
                throw new IOException("Could not discover managed datapack " + pack);
            }
        }
    }

    // Keep the old pack for the migration boot, then remove it after copying its definitions.
    public static void migrateLegacyPack(Path worldRoot, Path dataDirectory, String name) throws IOException {
        migrateLegacyPacks(List.of(worldRoot.resolve("datapacks").resolve(name)), dataDirectory, name);
    }

    public static void migrateLegacyPack(World world, Path dataDirectory, String name) throws IOException {
        Path root = worldRoot(world).resolve("datapacks").resolve(name);
        Path direct = world.getWorldFolder().toPath().resolve("datapacks").resolve(name);
        migrateLegacyPacks(sameNormalizedPath(root, direct) ? List.of(root) : List.of(direct, root),
                dataDirectory, name);
    }

    private static void migrateLegacyPacks(List<Path> sources, Path dataDirectory, String name) throws IOException {
        Path managed = managedPack(dataDirectory, name);
        for (Path legacy : sources) {
            if (!Files.exists(legacy)) continue;
            if (Files.isSymbolicLink(legacy)) {
                throw new IOException("Refusing to migrate a linked datapack " + legacy);
            }
            try (Stream<Path> paths = Files.walk(legacy)) {
                for (Path source : paths.filter(Files::isRegularFile).toList()) {
                    if (Files.isSymbolicLink(source)) {
                        throw new IOException("Refusing to migrate a linked datapack file " + source);
                    }
                    Path target = managed.resolve(legacy.relativize(source));
                    if (!source.getFileName().toString().equals("pack.mcmeta")) {
                        Files.createDirectories(target.getParent());
                        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            deleteRecursively(legacy);
        }
        writeIfChanged(managed.resolve(".legacy-migrated"), "Migrated to plugin-managed discovery.\n");
    }

    public static void archiveLegacyPack(Path legacy, Path dataDirectory) throws IOException {
        if (!Files.exists(legacy)) return;
        if (Files.isSymbolicLink(legacy)) throw new IOException("Refusing to archive a linked datapack " + legacy);
        Path backupRoot = dataDirectory.resolve("datapack-backups");
        Files.createDirectories(backupRoot);
        String name = legacy.getFileName().toString();
        Path backup = backupRoot.resolve(name);
        if (Files.exists(backup)) backup = backupRoot.resolve(name + "-" + System.currentTimeMillis());
        Files.move(legacy, backup);
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
