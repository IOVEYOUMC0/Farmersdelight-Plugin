package com.huidu.farmersdelight.resource;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.api.config.ConfigFileUpdater;
import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

public final class ResourceInstaller {

    private static final String CRAFTENGINE_RESOURCE_ROOT = "craftengine/farmersdelight";
    public static final Path CRAFTENGINE_RESOURCE_TARGET = Path.of("CraftEngine", "resources", "farmersdelight");

    private final FarmersDelightPlugin plugin;
    private final File pluginJar;

    public ResourceInstaller(FarmersDelightPlugin plugin, File pluginJar) {
        this.plugin = plugin;
        this.pluginJar = pluginJar;
    }

    public void installCraftEngineResourcesOnce() {
        Path pluginsFolder = plugin.getDataFolder().toPath().getParent();
        if (pluginsFolder == null) {
            I18n.logWarning("plugin.craftengine_resources_release_failed",
                    "error", "Unable to resolve the plugins folder.");
            return;
        }

        Path targetRoot = pluginsFolder.resolve(CRAFTENGINE_RESOURCE_TARGET);
        try {
            int changedFiles;
            if (Files.exists(targetRoot)) {
                changedFiles = plugin.getConfig().getBoolean("craftengine-resources.auto-completion", true)
                        ? copyBundledResourceFiles(targetRoot)
                        : 0;
                changedFiles += migrateKnownResourceFixes(targetRoot);
            } else {
                changedFiles = copyBundledResourceDirectory(targetRoot);
            }
            if (changedFiles > 0) {
                I18n.logInfo("plugin.craftengine_resources_released",
                        "path", targetRoot,
                        "count", changedFiles);
            }
        } catch (IOException e) {
            I18n.logWarning("plugin.craftengine_resources_release_failed", "error", e.getMessage());
        }
    }

    private int migrateKnownResourceFixes(Path targetRoot) throws IOException {
        int changed = migrateItemsEggTag(targetRoot.resolve("configuration").resolve("items.yml"));
        changed += migrateAnimatedGuiItem(targetRoot.resolve("configuration").resolve("gui.yml"));
        return changed;
    }

    private int migrateItemsEggTag(Path items) throws IOException {
        if (!Files.isRegularFile(items)) {
            return 0;
        }
        String content = Files.readString(items);
        String migrated = content
                .replace("\"#c:eggs\"", "\"#eggs\"")
                .replace("\"#minecraft:eggs\"", "\"#eggs\"");
        if (content.equals(migrated)) {
            return 0;
        }
        Files.writeString(items, migrated);
        return 1;
    }

    private int migrateAnimatedGuiItem(Path guiPath) throws IOException {
        if (!Files.isRegularFile(guiPath)) {
            return 0;
        }
        String resourcePath = CRAFTENGINE_RESOURCE_ROOT + "/configuration/gui.yml";
        try {
            YamlConfiguration bundled = ConfigFileUpdater.readBundledYaml(plugin, resourcePath);
            if (bundled == null) {
                return 0;
            }
            YamlConfiguration existing = ConfigFileUpdater.readYamlFile(guiPath);
            ConfigurationSection bundledItems = bundled.getConfigurationSection("items");
            ConfigurationSection animated = bundledItems == null
                    ? null : bundledItems.getConfigurationSection("farmersdelight:animated");
            if (animated == null) {
                return 0;
            }
            ConfigurationSection existingItems = existing.getConfigurationSection("items");
            if (existingItems != null
                    && (existingItems.getConfigurationSection("farmersdelight:animated") != null
                    || existingItems.isSet("farmersdelight:animated"))) {
                return 0;
            }
            if (existingItems == null) {
                if (existing.isSet("items")) {
                    return 0;
                }
                existingItems = existing.createSection("items");
            }
            ConfigFileUpdater.copySection(animated, existingItems.createSection("farmersdelight:animated"));
            ConfigFileUpdater.backup(guiPath);
            ConfigFileUpdater.tidy(existing);
            ConfigFileUpdater.writeStringAtomically(guiPath, existing.saveToString(), true);
            return 1;
        } catch (InvalidConfigurationException e) {
            throw new IOException("CraftEngine gui.yml is not valid YAML", e);
        }
    }

    private int copyBundledResourceFiles(Path targetRoot) throws IOException {
        List<String> resourcePaths = listBundledResourceFiles(ResourceInstaller.CRAFTENGINE_RESOURCE_ROOT);
        if (resourcePaths.isEmpty()) {
            throw new IOException("No bundled CraftEngine resources found at " + ResourceInstaller.CRAFTENGINE_RESOURCE_ROOT);
        }

        boolean debug = plugin.isDebugEnabled("resource");
        int copiedFiles = 0;
        int skippedFiles = 0;
        Files.createDirectories(targetRoot);
        for (String resourcePath : resourcePaths) {
            String relativePath = resourcePath.substring(ResourceInstaller.CRAFTENGINE_RESOURCE_ROOT.length() + 1);
            Path targetPath = resolveSafeChild(targetRoot, relativePath);
            if (Files.exists(targetPath)) {
                skippedFiles++;
                if (debug) {
                    I18n.logInfo("plugin.resource_debug_skipped", "path", relativePath);
                }
                continue;
            }

            if (debug) {
                I18n.logInfo("plugin.resource_debug_added", "path", relativePath);
            }
            Files.createDirectories(Objects.requireNonNull(targetPath.getParent(), "targetPath parent"));
            Path tempFile = Files.createTempFile(targetPath.getParent(), "fd-ce-resource-", ".tmp");
            boolean moved = false;
            try (InputStream inputStream = plugin.getResource(resourcePath)) {
                if (inputStream == null) {
                    throw new IOException("Bundled " + resourcePath + " was not found in the plugin jar.");
                }
                Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(tempFile, targetPath, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(tempFile, targetPath);
                }
                moved = true;
                copiedFiles++;
            } finally {
                if (!moved) {
                    Files.deleteIfExists(tempFile);
                }
            }
        }
        if (debug) {
            I18n.logInfo("plugin.resource_debug_completion", "count", copiedFiles, "skipped", skippedFiles);
        }
        return copiedFiles;
    }

    private int copyBundledResourceDirectory(Path targetRoot) throws IOException {
        List<String> resourcePaths = listBundledResourceFiles(ResourceInstaller.CRAFTENGINE_RESOURCE_ROOT);
        if (resourcePaths.isEmpty()) {
            throw new IOException("No bundled CraftEngine resources found at " + ResourceInstaller.CRAFTENGINE_RESOURCE_ROOT);
        }

        boolean debug = plugin.isDebugEnabled("resource");
        Path parent = Objects.requireNonNull(targetRoot.getParent(), "targetRoot parent");
        Files.createDirectories(parent);
        Path tempRoot = Files.createTempDirectory(parent, targetRoot.getFileName() + "-");
        boolean moved = false;

        try {
            int copiedFiles = 0;
            for (String resourcePath : resourcePaths) {
                String relativePath = resourcePath.substring(ResourceInstaller.CRAFTENGINE_RESOURCE_ROOT.length() + 1);
                Path targetPath = resolveSafeChild(tempRoot, relativePath);
                if (debug) {
                    I18n.logInfo("plugin.resource_debug_added", "path", relativePath);
                }
                Files.createDirectories(Objects.requireNonNull(targetPath.getParent(), "targetPath parent"));
                try (InputStream inputStream = plugin.getResource(resourcePath)) {
                    if (inputStream == null) {
                        throw new IOException("Bundled " + resourcePath + " was not found in the plugin jar.");
                    }
                    Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    copiedFiles++;
                }
            }

            try {
                Files.move(tempRoot, targetRoot, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tempRoot, targetRoot);
            }
            moved = true;
            if (debug) {
                I18n.logInfo("plugin.resource_debug_first_install", "count", copiedFiles, "dir", targetRoot.toString());
            }
            return copiedFiles;
        } finally {
            if (!moved) {
                deleteTreeQuietly(tempRoot);
            }
        }
    }

    private Path resolveSafeChild(Path root, String relativePath) throws IOException {
        Path normalizedRoot = root.normalize();
        Path targetPath = normalizedRoot.resolve(relativePath).normalize();
        if (!targetPath.startsWith(normalizedRoot)) {
            throw new IOException("Invalid bundled resource path: " + relativePath);
        }
        return targetPath;
    }

    private List<String> listBundledResourceFiles(String resourceRoot) throws IOException {
        // Primary strategy: scan the plugin jar's entries directly. Packaging tools may omit directory entries,
        // so getClassLoader().getResource(<directory>) can return null and the URL-based walk below
        // finds nothing ("No bundled CraftEngine resources found"). Reading file entries by prefix
        // is unaffected by missing directory entries and per-platform classloader differences.
        List<String> fromJar = listJarFileResourceFiles(resourceRoot);
        if (fromJar != null) {
            return fromJar;
        }

        // Fallback for exploded directory/IDE/test-run scenarios, where the plugin is not packaged as a jar file.
        URL resourceUrl = getClass().getClassLoader().getResource(resourceRoot);
        if (resourceUrl == null) {
            return List.of();
        }

        try {
            URI resourceUri = resourceUrl.toURI();
            if ("file".equals(resourceUrl.getProtocol())) {
                return listFileResourceFiles(resourceRoot, Path.of(resourceUri));
            }
            if ("jar".equals(resourceUrl.getProtocol())) {
                return listJarResourceFiles(resourceRoot, resourceUri);
            }
            throw new IOException("Unsupported bundled resource protocol: " + resourceUrl.getProtocol());
        } catch (URISyntaxException e) {
            throw new IOException("Invalid bundled resource URI for " + resourceRoot, e);
        }
    }

    private List<String> listJarFileResourceFiles(String resourceRoot) throws IOException {
        if (pluginJar == null || !pluginJar.isFile()) {
            return null;
        }

        String prefix = resourceRoot.endsWith("/") ? resourceRoot : resourceRoot + "/";
        List<String> resourcePaths = new ArrayList<>();
        try (JarFile jarFile = new JarFile(pluginJar)) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (name.startsWith(prefix)) {
                    resourcePaths.add(name);
                }
            }
        }
        resourcePaths.sort(Comparator.naturalOrder());
        return resourcePaths;
    }

    private List<String> listFileResourceFiles(String resourceRoot, Path rootPath) throws IOException {
        try (Stream<Path> stream = Files.walk(rootPath)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(rootPath::relativize)
                    .map(path -> resourceRoot + "/" + path.toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }

    private List<String> listJarResourceFiles(String resourceRoot, URI resourceUri) throws IOException {
        String uriText = resourceUri.toString();
        int separatorIndex = uriText.indexOf("!/");
        if (separatorIndex < 0) {
            throw new IOException("Invalid jar resource URI: " + resourceUri);
        }

        URI jarUri = URI.create(uriText.substring(0, separatorIndex));
        FileSystem fileSystem = null;
        boolean closeFileSystem = false;

        try {
            try {
                fileSystem = FileSystems.newFileSystem(jarUri, Map.of());
                closeFileSystem = true;
            } catch (FileSystemAlreadyExistsException ignored) {
                fileSystem = FileSystems.getFileSystem(jarUri);
            }

            Path rootPath = fileSystem.getPath("/" + resourceRoot);
            try (Stream<Path> stream = Files.walk(rootPath)) {
                return stream
                        .filter(Files::isRegularFile)
                        .map(rootPath::relativize)
                        .map(path -> resourceRoot + "/" + path.toString().replace('\\', '/'))
                        .sorted()
                        .toList();
            }
        } finally {
            if (closeFileSystem && fileSystem != null) {
                fileSystem.close();
            }
        }
    }

    private void deleteTreeQuietly(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : stream.sorted((left, right) -> right.getNameCount() - left.getNameCount()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }
}
