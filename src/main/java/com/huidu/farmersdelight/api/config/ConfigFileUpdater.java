package com.huidu.farmersdelight.api.config;

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

/**
 * Keeps a config file an operator already has in step with the one the running build ships: renames paths
 * that moved, deletes paths nothing reads any more, and fills in settings that a later version introduced,
 * leaving every value the operator set exactly as it was.
 *
 * This is the machinery only. What to rename, what to retire and which sections list content instead of
 * settings is data, and comes from the caller as a ConfigUpdatePolicy. Nothing here logs and nothing here
 * decides when to write: the update returns a ConfigUpdateReport and the caller reports it in its own
 * words and writes the file if it wants to. That split is what lets one plugin and its addons share the
 * behaviour without sharing a voice, a language file or a set of config tables.
 *
 * A plugin with several files bootstraps each of them with its own policy; the class holds no state.
 */
public final class ConfigFileUpdater {

    private ConfigFileUpdater() {
    }

    /**
     * Applies the dump settings a user-facing YAML file is written with, and must be called on the
     * configuration right before YamlConfiguration.save / saveToString.
     *
     * Bukkit's YAML dumper defaults to a line width of 80 characters, so any value longer than that is
     * folded across several physical lines when the file is rewritten. Item id lists, message templates and
     * long descriptions then come back as multi-line blocks that are hard to read and easy to break by hand.
     * An effectively unlimited width keeps one value on one line. The indent is pinned to 2 spaces so a
     * rewritten file matches the bundled files, and comment parsing stays on so the header documentation and
     * the per-key comments survive the load/save round trip.
     *
     * Saving still goes through Bukkit's own YamlConfiguration, which is what preserves comments and
     * ConfigurationSerializable values; this only adjusts its dump options. Anything that is not a
     * YamlConfiguration has no such options and is left alone.
     */
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
     * Runs the whole update against one file in the only order that is correct, and returns what it did.
     *
     * Renaming runs first: a rename is skipped when the new path is already set, so filling in the bundled
     * defaults first would plant the new path, make every rename a no-op and silently drop the values the
     * operator had configured under the old name. Retired keys are dropped next, after a rename has moved a
     * value to its current path and before the merge writes anything, so a setting nothing reads is not
     * carried forward again. The additive merge runs last.
     *
     * Neither argument is written to disk here. The caller writes existing when the report says something
     * changed, having called tidy on it first.
     */
    public static ConfigUpdateReport applyTo(ConfigurationSection bundled, ConfigurationSection existing,
                                             ConfigUpdatePolicy policy) {
        List<ConfigKeyRename> migrated = applyMigrations(existing, policy.migrations());
        List<String> retired = removeKeys(existing, policy.retiredKeys());
        int added = copyMissingKeys(bundled, existing, policy.registrySections());
        return new ConfigUpdateReport(migrated, retired, added);
    }

    /**
     * Brings a plugin's own config.yml up to date and writes it back, doing every step in the order they have to
     * run. Use this rather than the individual steps unless the plugin needs to interleave something between
     * them: the steps are order-dependent, and a caller that saves without pinning the dump options first gets a
     * file whose long values are folded across several lines the first time it is rewritten.
     *
     * Nothing is written when nothing changed. A copy of the file is taken before the rewrite because the same
     * pass that adds settings also deletes values the operator wrote; if that copy cannot be written the update
     * still goes ahead and the reason is carried on the report.
     *
     * Returns what changed so the caller can report it in its own wording and its own language files.
     */
    public static ConfigUpdateReport updateMainConfig(Plugin plugin, ConfigUpdatePolicy policy)
            throws IOException, InvalidConfigurationException {
        YamlConfiguration bundled = readBundledYaml(plugin, "config.yml");
        if (bundled == null) {
            return new ConfigUpdateReport(List.of(), List.of(), 0);
        }
        ConfigUpdateReport report = applyTo(bundled, plugin.getConfig(), policy);
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
        return new ConfigUpdateReport(report.migratedKeys(), report.retiredKeys(), report.addedKeys(), backupError);
    }

    /**
     * Moves the value at each rename's old path to its new path and clears the old path. A rename whose new
     * path is already set is skipped, so a file that has been updated by hand keeps the value it carries
     * there. Returns the renames that actually moved something, in the order they were applied.
     *
     * A section is recreated key by key at the new path; comments are not carried across a rename.
     */
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

    private static void copySection(ConfigurationSection source, ConfigurationSection target) {
        for (String key : source.getKeys(false)) {
            ConfigurationSection child = source.getConfigurationSection(key);
            if (child != null) {
                copySection(child, target.createSection(key));
            } else {
                target.set(key, source.get(key));
            }
        }
    }

    /**
     * Deletes the given paths from the configuration and returns the ones that were actually there, in the
     * order they were listed. Uses the same presence test the renames use, so both agree on what the
     * operator's file carries. This discards values the operator wrote, so a caller that owns the file
     * should keep a copy of it before it writes the result.
     */
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

    /**
     * Walks every key of the bundled configuration and sets the ones the operator's configuration does not
     * have, together with the comment that documents them. Values already present are never overwritten, so
     * key paths and operator choices stay exactly as they were. Entries of the given registry sections are
     * skipped once the operator's file has that section. Returns the number of value keys added; sections
     * themselves are not counted.
     */
    public static int copyMissingKeys(ConfigurationSection bundled, ConfigurationSection existing,
                                      List<String> registrySections) {
        int added = 0;
        List<String> candidateSections = new ArrayList<>();
        // Snapshot taken before anything is written: suppression has to reflect the file as the operator left
        // it. Testing the live section instead lets the first entry written into an absent registry section
        // make that section exist, which then suppresses every remaining sibling and leaves a half-populated
        // entry behind.
        Set<String> sectionsOperatorAlreadyHad = new HashSet<>();
        for (String section : registrySections) {
            if (existing.contains(section, true)) {
                sectionsOperatorAlreadyHad.add(section);
            }
        }
        for (String key : bundled.getKeys(true)) {
            // Registry sections list content rather than settings, and deleting an entry there is how an
            // operator disables it. Adding entries back one by one would silently undo that, so these
            // sections are only filled in when the operator's file does not have them at all.
            if (isSuppressedRegistryEntry(key, registrySections, sectionsOperatorAlreadyHad)) {
                continue;
            }
            // getKeys(true) yields a section before its children, so a section still missing at this point is
            // one the operator's file does not have at all.
            if (bundled.isConfigurationSection(key)) {
                if (!existing.contains(key, true)) {
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
        for (String sectionKey : candidateSections) {
            // A section exists now only because one of its values was just added; document those headers too.
            if (existing.contains(sectionKey, true)) {
                copyComments(bundled, existing, sectionKey);
            }
        }
        return added;
    }

    /**
     * True when the key is a descendant of a registry section the operator's file already had, in which case
     * a missing entry means the operator removed it rather than that the entry is new. The section node
     * itself is never suppressed, so a section the operator does not have is still created.
     */
    private static boolean isSuppressedRegistryEntry(String key, List<String> registrySections,
                                                     Set<String> sectionsOperatorAlreadyHad) {
        for (String section : registrySections) {
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

    /**
     * Loads a YAML resource bundled in the plugin jar. Returns null when the jar has no such resource, which
     * is the one outcome that is not an error: a build may legitimately not ship the file. Malformed content
     * is raised so the caller can report it in its own words.
     */
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

    /**
     * Loads a YAML file from disk as UTF-8, without Bukkit's jar-default layer. A file the plugin does not
     * own its getConfig for, such as a second config file, is read this way.
     */
    public static YamlConfiguration readYamlFile(Path file) throws IOException, InvalidConfigurationException {
        YamlConfiguration configuration = new YamlConfiguration();
        try (InputStreamReader reader = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) {
            configuration.load(reader);
        }
        return configuration;
    }

    /**
     * Keeps a timestamped copy of a file next to it, named file.yyyyMMdd-HHmmss.bak. Two backups taken in
     * the same second collapse into one file rather than piling up.
     */
    public static void backup(Path file) throws IOException {
        String fileName = file.getFileName().toString();
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String backupName = fileName + "." + timestamp + ".bak";
        Files.copy(file, file.resolveSibling(backupName), StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * True when the file on disk cannot be trusted and should be replaced from the jar: it does not parse as
     * YAML, it cannot be read at all, or it contains the Unicode replacement character, which is what a file
     * saved in the wrong encoding leaves behind.
     */
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

    /**
     * Writes a resource bundled in the plugin jar to a file on disk, atomically. The resource must decode as
     * UTF-8, and a YAML resource must parse, so a broken build cannot overwrite a working file with rubbish.
     * With replace false an existing target is an error rather than being overwritten.
     */
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

    /**
     * Writes text to a file through a temporary file in the same directory and an atomic move, so a crash
     * or a full disk leaves the previous file intact rather than a half-written one. Falls back to a plain
     * move on a filesystem that cannot move atomically. With replace false an existing target is an error.
     */
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
