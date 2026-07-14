package com.huidu.farmersdelight.config;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.i18n.I18n;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

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

/**
 * Prepares the plugin's config.yml / gui.yml on disk: writes the bundled defaults when missing,
 * restores an unreadable/corrupt file from the jar (keeping a timestamped backup), atomically writes
 * a single bundled resource, and migrates renamed config keys. Extracted from the plugin main class so
 * the config-file bootstrap concern lives in one focused place. Operations run through the plugin's
 * public config API (getConfig / saveConfig / reloadConfig / getResource).
 */
public final class ConfigBootstrap {

    private static final String[][] CONFIG_KEY_MIGRATIONS = {
            {"knife-drops", "mob-extra-drops"},
            {"entity-extra-drops", "mob-extra-drops"},
            {"knife-drop-tools", "mob-extra-drop-tools"},
            {"entity-extra-drop-tools", "mob-extra-drop-tools"}
    };

    private final FarmersDelightPlugin plugin;

    public ConfigBootstrap(FarmersDelightPlugin plugin) {
        this.plugin = plugin;
    }

    public void ensureConfigDefaults() {
        Path dataFolder = plugin.getDataFolder().toPath();
        Path configPath = dataFolder.resolve("config.yml");
        Path guiPath = dataFolder.resolve("gui.yml");
        try {
            Files.createDirectories(dataFolder);
            if (Files.notExists(configPath)) {
                writeBundledConfig(configPath);
            }
            writeBundledResourceIfMissing("gui.yml", guiPath);

            if (shouldRestoreConfig(configPath)) {
                backupBrokenConfig(configPath);
                writeBundledConfig(configPath);
                I18n.logWarning("config_restored_unreadable", "file", "config.yml");
            }
            if (shouldRestoreConfig(guiPath)) {
                backupBrokenConfig(guiPath);
                writeBundledResource("gui.yml", guiPath, true);
                I18n.logWarning("config_restored_unreadable", "file", "gui.yml");
            }
        } catch (IOException e) {
            I18n.logWarning("config_prepare_failed", "error", e.getMessage());
        }
    }

    public void migrateConfigKeys() {
        boolean changed = false;
        for (String[] migration : CONFIG_KEY_MIGRATIONS) {
            changed |= migrateConfigSection(migration[0], migration[1]);
        }
        if (changed) {
            plugin.saveConfig();
            plugin.reloadConfig();
        }
    }

    private boolean migrateConfigSection(String oldPath, String newPath) {
        if (plugin.getConfig().isSet(newPath) || !plugin.getConfig().isSet(oldPath)) {
            return false;
        }
        ConfigurationSection oldSection = plugin.getConfig().getConfigurationSection(oldPath);
        if (oldSection != null) {
            ConfigurationSection newSection = plugin.getConfig().createSection(newPath);
            copyConfigSection(oldSection, newSection);
        } else {
            plugin.getConfig().set(newPath, plugin.getConfig().get(oldPath));
        }
        plugin.getConfig().set(oldPath, null);
        I18n.logInfo("config_key_migrated", "old", oldPath, "new", newPath);
        return true;
    }

    private void copyConfigSection(ConfigurationSection source, ConfigurationSection target) {
        for (String key : source.getKeys(false)) {
            ConfigurationSection child = source.getConfigurationSection(key);
            if (child != null) {
                copyConfigSection(child, target.createSection(key));
            } else {
                target.set(key, source.get(key));
            }
        }
    }

    private boolean shouldRestoreConfig(Path configPath) {
        if (!isYamlReadable(configPath)) {
            return true;
        }
        try {
            return Files.readString(configPath, StandardCharsets.UTF_8).indexOf('�') >= 0;
        } catch (IOException e) {
            return true;
        }
    }

    private boolean isYamlReadable(Path configPath) {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            try (InputStreamReader reader = new InputStreamReader(Files.newInputStream(configPath), StandardCharsets.UTF_8)) {
                yaml.load(reader);
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void backupBrokenConfig(Path configPath) throws IOException {
        String fileName = configPath.getFileName().toString();
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String backupName = fileName + "." + timestamp + ".bak";
        Files.copy(configPath, configPath.resolveSibling(backupName), StandardCopyOption.REPLACE_EXISTING);
    }

    private void writeBundledConfig(Path configPath) throws IOException {
        writeBundledResource("config.yml", configPath, true);
    }

    private void writeBundledResourceIfMissing(String resourcePath, Path targetPath) throws IOException {
        if (Files.notExists(targetPath)) {
            writeBundledResource(resourcePath, targetPath, false);
        }
    }

    private void writeBundledResource(String resourcePath, Path targetPath, boolean replace) throws IOException {
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

    private String decodeUtf8Resource(byte[] bytes, String resourcePath) throws IOException {
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

    private boolean isYamlResource(String resourcePath) {
        return resourcePath != null
                && (resourcePath.equals("config.yml") || resourcePath.equals("gui.yml"));
    }

    private boolean isYamlContentReadable(String content) {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(content);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void writeStringAtomically(Path targetPath, String content, boolean replace) throws IOException {
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
