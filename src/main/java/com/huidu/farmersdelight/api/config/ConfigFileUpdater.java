package com.huidu.farmersdelight.api.config;

import com.huidu.farmersdelight.i18n.I18n;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.file.YamlConfigurationOptions;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ConfigFileUpdater {

    private ConfigFileUpdater() {
    }

    public static void tidy(FileConfiguration configuration) {
        if (!(configuration instanceof YamlConfiguration yaml)) {
            return;
        }
        YamlConfigurationOptions options = yaml.options();
        options.width(Integer.MAX_VALUE);
        options.indent(2);
        options.parseComments(true);
    }

    /**
     * Monotonic generation counter for a config file. Key-level diffing can add a missing key, but it
     * cannot tell a fresh install from an upgrade, nor spot a downgrade, nor let a migration run exactly
     * once. The bundled file carries the current number; the deployed file is stamped with it after a
     * successful update.
     */
    public static final String CONFIG_VERSION_KEY = "config-version";

    /** The generation this build ships for the given bundled config (0 when the file declares none). */
    public static int bundledVersion(ConfigurationSection bundled) {
        return bundled == null ? 0 : bundled.getInt(CONFIG_VERSION_KEY, 0);
    }

    /** The generation currently on disk (0 when the file predates versioning or declares none). */
    public static int deployedVersion(ConfigurationSection existing) {
        return existing == null ? 0 : existing.getInt(CONFIG_VERSION_KEY, 0);
    }

    public static ConfigUpdateReport applyTo(ConfigurationSection bundled, ConfigurationSection existing,
                                             ConfigUpdatePolicy policy) {
        int fromVersion = deployedVersion(existing);
        int toVersion = bundledVersion(bundled);
        List<ConfigKeyRename> migrated = applyMigrations(existing, policy.migrations());
        List<String> retired = removeKeys(existing, policy.retiredKeys());
        int added = copyMissingKeys(bundled, existing, policy.registrySections());
        // Stamp last, so the file only claims the new generation once every other step succeeded.
        // A downgrade is recorded but NOT stamped: rewriting a newer file's version backwards would
        // hide the mismatch from the next startup.
        if (existing != null && toVersion >= fromVersion) {
            existing.set(CONFIG_VERSION_KEY, toVersion);
        }
        return new ConfigUpdateReport(migrated, retired, added, null, fromVersion, toVersion);
    }

    public static ConfigUpdateReport updateMainConfig(Plugin plugin, ConfigUpdatePolicy policy)
            throws IOException, InvalidConfigurationException {
        YamlConfiguration bundled = readBundledYaml(plugin, "config.yml");
        if (bundled == null) {
            return new ConfigUpdateReport(List.of(), List.of(), 0);
        }
        ConfigUpdateReport report = applyTo(bundled, plugin.getConfig(), policy);
        if (report.downgraded()) {
            I18n.logWarning("plugin.config_version_downgraded", "from", report.fromVersion(),
                    "to", report.toVersion());
            return report;
        }
        if (!report.changed()) {
            return report;
        }
        String backupError = null;
        try {
            backup(plugin.getDataFolder().toPath().resolve("config.yml"));
        } catch (IOException e) {
            backupError = String.valueOf(e.getMessage());
        }
        tidy(plugin.getConfig());
        plugin.saveConfig();
        plugin.reloadConfig();
        return new ConfigUpdateReport(report.migratedKeys(), report.retiredKeys(), report.addedKeys(),
                backupError, report.fromVersion(), report.toVersion());
    }

    public static List<ConfigKeyRename> applyMigrations(ConfigurationSection config,
                                                        List<ConfigKeyRename> migrations) {
        List<ConfigKeyRename> applied = new ArrayList<>();
        for (ConfigKeyRename rename : migrations) {
            if (migrateSection(config, rename.oldPath(), rename.newPath())) {
                applied.add(rename);
            }
        }
        return applied;
    }

    private static boolean migrateSection(ConfigurationSection config, String oldPath, String newPath) {
        if (config.isSet(newPath) || !config.isSet(oldPath)) {
            return false;
        }
        ConfigurationSection oldSection = config.getConfigurationSection(oldPath);
        if (oldSection != null) {
            ConfigurationSection newSection = config.createSection(newPath);
            copySection(oldSection, newSection);
        } else {
            config.set(newPath, config.get(oldPath));
        }
        config.set(oldPath, null);
        return true;
    }

    public static boolean copyPathIfMissing(ConfigurationSection config, String sourcePath, String targetPath) {
        if (config.isSet(targetPath) || !config.isSet(sourcePath)) {
            return false;
        }
        ConfigurationSection sourceSection = config.getConfigurationSection(sourcePath);
        if (sourceSection != null) {
            copySection(sourceSection, config.createSection(targetPath));
        } else {
            config.set(targetPath, config.get(sourcePath));
        }
        return true;
    }

    public static void copySection(ConfigurationSection source, ConfigurationSection target) {
        for (String key : source.getKeys(false)) {
            ConfigurationSection child = source.getConfigurationSection(key);
            if (child != null) {
                copySection(child, target.createSection(key));
            } else {
                target.set(key, source.get(key));
            }
        }
    }

    public static List<String> removeKeys(ConfigurationSection config, List<String> paths) {
        List<String> removed = new ArrayList<>();
        for (String path : paths) {
            if (config.isSet(path)) {
                config.set(path, null);
                removed.add(path);
            }
        }
        return removed;
    }

    public static int copyMissingKeys(ConfigurationSection bundled, ConfigurationSection existing,
                                      List<String> registrySections) {
        int added = 0;
        List<String> candidateSections = registrySections.isEmpty() ? null : new ArrayList<>();
        // Snapshot taken before anything is written: suppression has to reflect the file as the operator left
        // it. Testing the live section instead lets the first entry written into an absent registry section
        // make that section exist, which then suppresses every remaining sibling and leaves a half-populated
        // entry behind.
        Set<String> sectionsOperatorAlreadyHad = registrySections.isEmpty() ? null : new HashSet<>();
        if (sectionsOperatorAlreadyHad != null) {
            for (String section : registrySections) {
                if (existing.contains(section, true)) {
                    sectionsOperatorAlreadyHad.add(section);
                }
            }
        }
        for (String key : bundled.getKeys(true)) {
            // Registry sections list content rather than settings, and deleting an entry there is how an
            // operator disables it. Adding entries back one by one would silently undo that, so these
            // sections are only filled in when the operator's file does not have them at all.
            if (sectionsOperatorAlreadyHad != null
                    && isSuppressedRegistryEntry(key, registrySections, sectionsOperatorAlreadyHad)) {
                continue;
            }
            // getKeys(true) yields a section before its children. A section still missing here is one the
            // operator's file does not have at all; creating it also brings its children in, so those children
            // are then found as "present" and only their comments are copied below.
            if (bundled.isConfigurationSection(key)) {
                if (candidateSections != null && !existing.contains(key, true)) {
                    candidateSections.add(key);
                }
                continue;
            }
            // contains(path, true) ignores the jar defaults Bukkit attaches to getConfig(); plain contains
            // would report every bundled key as already present and turn the whole merge into a no-op.
            if (existing.contains(key, true)) {
                continue;
            }
            Object value = bundled.get(key);
            // A key with no value in the bundled file would set null, which removes the path again: it would
            // count as added on every startup and rewrite the file each time.
            if (value == null) {
                continue;
            }
            existing.set(key, value);
            copyComments(bundled, existing, key);
            added++;
        }
        if (candidateSections != null) {
            for (String sectionKey : candidateSections) {
                // A section exists now only because one of its values was just added; document those headers too.
                if (existing.contains(sectionKey, true)) {
                    copyComments(bundled, existing, sectionKey);
                }
            }
        }
        return added;
    }

    private static boolean isSuppressedRegistryEntry(String key, List<String> registrySections,
                                                     Set<String> sectionsOperatorAlreadyHad) {
        for (String section : registrySections) {
            // The section name itself is never suppressed, only its entries, and a sibling section whose name
            // merely starts with the same characters ("items" vs "items-extra") is not an entry of it: the
            // trailing dot is part of the match.
            if (key.equals(section) || !key.startsWith(section + ".")) {
                continue;
            }
            if (sectionsOperatorAlreadyHad.contains(section)) {
                return true;
            }
        }
        return false;
    }

    private static void copyComments(ConfigurationSection bundled, ConfigurationSection existing, String key) {
        List<String> comments = bundled.getComments(key);
        if (!comments.isEmpty()) {
            existing.setComments(key, comments);
        }
    }

    public static YamlConfiguration readBundledYaml(Plugin plugin, String resourcePath)
            throws IOException, InvalidConfigurationException {
        try (InputStream stream = plugin.getResource(resourcePath)) {
            if (stream == null) {
                return null;
            }
            YamlConfiguration bundled = new YamlConfiguration();
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                bundled.load(reader);
            }
            return bundled;
        }
    }

    public static YamlConfiguration readYamlFile(Path file) throws IOException, InvalidConfigurationException {
        YamlConfiguration configuration = new YamlConfiguration();
        try (InputStreamReader reader = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) {
            configuration.load(reader);
        }
        return configuration;
    }

    public static void backup(Path file) throws IOException {
        String fileName = file.getFileName().toString();
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String backupName = fileName + "." + timestamp + ".bak";
        Files.copy(file, file.resolveSibling(backupName), StandardCopyOption.REPLACE_EXISTING);
    }

    public static boolean needsRestore(Path file) {
        if (!isYamlReadable(file)) {
            return true;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8).indexOf('�') >= 0;
        } catch (IOException e) {
            return true;
        }
    }

    private static boolean isYamlReadable(Path file) {
        try {
            readYamlFile(file);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static void installBundledResource(Plugin plugin, String resourcePath, Path targetPath, boolean replace)
            throws IOException {
        try (InputStream inputStream = plugin.getResource(resourcePath)) {
            if (inputStream == null) {
                throw new IOException("Bundled " + resourcePath + " was not found in the plugin jar.");
            }
            String content = decodeUtf8Resource(inputStream.readAllBytes(), resourcePath);
            if (isYamlResource(resourcePath) && !isYamlContentReadable(content)) {
                throw new IOException("Bundled " + resourcePath + " is not valid YAML.");
            }
            writeStringAtomically(targetPath, content, replace);
        }
    }

    private static String decodeUtf8Resource(byte[] bytes, String resourcePath) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IOException("Bundled " + resourcePath + " is not valid UTF-8.", e);
        }
    }

    private static boolean isYamlResource(String resourcePath) {
        if (resourcePath == null) {
            return false;
        }
        String lower = resourcePath.toLowerCase(Locale.ROOT);
        return lower.endsWith(".yml") || lower.endsWith(".yaml");
    }

    private static boolean isYamlContentReadable(String content) {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(content);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static void writeStringAtomically(Path targetPath, String content, boolean replace) throws IOException {
        Path parent = targetPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (!replace && Files.exists(targetPath)) {
            throw new IOException("Target already exists: " + targetPath);
        }

        Path tempFile = parent == null
                ? Files.createTempFile(targetPath.getFileName().toString(), ".tmp")
                : Files.createTempFile(parent, targetPath.getFileName().toString(), ".tmp");
        boolean moved = false;
        try {
            Files.writeString(tempFile, content, StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            try {
                if (replace) {
                    Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } else {
                    Files.move(tempFile, targetPath, StandardCopyOption.ATOMIC_MOVE);
                }
            } catch (AtomicMoveNotSupportedException ignored) {
                if (replace) {
                    Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(tempFile, targetPath);
                }
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(tempFile);
            }
        }
    }
}
