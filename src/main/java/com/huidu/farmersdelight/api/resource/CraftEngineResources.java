package com.huidu.farmersdelight.api.resource;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Installs bundled CraftEngine files without overwriting server edits and migrates known CE incompatibilities. */
public final class CraftEngineResources {

    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");

    private CraftEngineResources() {
    }

    /** Installs missing files, restores later deletions, and migrates legacy position arguments. */
    public static int release(JavaPlugin plugin, String namespace) {
        return release(plugin, namespace, true);
    }

    /**
     * Installs missing files from the addon's jar. When completeExisting is false, an existing
     * namespace directory is left untouched.
     */
    public static int release(JavaPlugin plugin, String namespace, boolean completeExisting) {
        if (plugin == null) {
            return 0;
        }
        try {
            Path pluginsDirectory = plugin.getDataFolder().toPath().getParent();
            if (pluginsDirectory == null) {
                return 0;
            }
            URI source = plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI();
            Path jar = Path.of(source);
            if (!Files.isRegularFile(jar)) {
                return 0;
            }
            return release(jar, pluginsDirectory, namespace, completeExisting);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to release CraftEngine resources for " + namespace + ": "
                    + e.getMessage());
            return 0;
        }
    }

    static int release(Path jar, Path pluginsDirectory, String namespace, boolean completeExisting) throws IOException {
        if (namespace == null || !NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("Invalid CraftEngine namespace: " + namespace);
        }
        Path resourcesRoot = pluginsDirectory.resolve("CraftEngine").resolve("resources").normalize();
        Path targetRoot = resourcesRoot.resolve(namespace).normalize();
        if (!targetRoot.startsWith(resourcesRoot)) {
            return 0;
        }

        if (Files.exists(targetRoot) && !completeExisting) {
            return migrateLegacyPositionArguments(targetRoot);
        }

        String prefix = "craftengine/" + namespace + "/";
        int copied = 0;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith(prefix)) {
                    continue;
                }
                Path target = targetRoot.resolve(entry.getName().substring(prefix.length())).normalize();
                if (!target.startsWith(targetRoot) || Files.exists(target)) {
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (InputStream input = zip.getInputStream(entry)) {
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                    copied++;
                }
            }
        }
        return copied + migrateLegacyPositionArguments(targetRoot);
    }

    private static int migrateLegacyPositionArguments(Path targetRoot) throws IOException {
        if (!Files.isDirectory(targetRoot)) {
            return 0;
        }
        int changed = 0;
        try (var paths = Files.walk(targetRoot)) {
            for (Path path : paths.filter(Files::isRegularFile).filter(CraftEngineResources::isTextResource).toList()) {
                String content = Files.readString(path);
                String migrated = content
                        .replace("<arg:block.block_x>", "<arg:position.block_x>")
                        .replace("<arg:block.block_y>", "<arg:position.block_y>")
                        .replace("<arg:block.block_z>", "<arg:position.block_z>");
                if (!content.equals(migrated)) {
                    Files.writeString(path, migrated);
                    changed++;
                }
            }
        }
        return changed;
    }

    private static boolean isTextResource(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".json");
    }
}
