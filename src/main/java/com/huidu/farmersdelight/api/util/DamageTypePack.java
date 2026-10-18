package com.huidu.farmersdelight.api.util;

import com.google.gson.Gson;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.stream.Stream;

/** Loads damage registry definitions from YAML and writes the startup pack. */
public final class DamageTypePack {
    private static final Gson JSON = new Gson();

    private DamageTypePack() {
    }

    public static boolean prepare(Path configuration, InputStream defaults, Path pack,
                                  String description, String version) throws IOException {
        final YamlConfiguration yaml;
        try {
            yaml = DatapackSupport.startupConfiguration(configuration, defaults);
        } catch (InvalidConfigurationException failure) {
            throw new IOException("Invalid damage registry configuration " + configuration, failure);
        }
        if (!yaml.isConfigurationSection("damage-types")) {
            throw new IOException("Missing damage-types mapping in " + configuration);
        }
        for (String field : List.of("damage-types", "tags")) {
            if (yaml.contains(field) && !yaml.isConfigurationSection(field)) {
                throw new IOException("Expected a mapping for " + field + " in " + configuration);
            }
        }
        Map<Path, String> files = new LinkedHashMap<>();
        collect(yaml.getConfigurationSection("damage-types"), pack, "damage_type", files);
        collect(yaml.getConfigurationSection("tags"), pack, "tags/damage_type", files);
        boolean changed = false;
        for (var entry : files.entrySet()) {
            changed |= DatapackSupport.writeIfChanged(entry.getKey(), entry.getValue());
        }
        if (Files.isDirectory(pack.resolve("data"))) {
            try (Stream<Path> paths = Files.walk(pack.resolve("data"))) {
                for (Path file : paths.filter(Files::isRegularFile).toList()) {
                    String relative = pack.relativize(file).toString().replace('\\', '/');
                    if (relative.endsWith(".json") && relative.contains("/damage_type/") && !files.containsKey(file)) {
                        changed |= Files.deleteIfExists(file);
                    }
                }
            }
        }
        return DatapackSupport.writeIfChanged(pack.resolve("pack.mcmeta"),
                DatapackSupport.renderPackMetadata(description, version)) || changed;
    }

    private static void collect(ConfigurationSection section, Path pack, String kind,
                                Map<Path, String> files) throws IOException {
        if (section == null) return;
        for (var entry : section.getValues(false).entrySet()) {
            NamespacedKey key = NamespacedKey.fromString(entry.getKey());
            if (key == null || entry.getKey().indexOf(':') < 0) {
                throw new IOException("Invalid registry ID: " + entry.getKey());
            }
            for (String segment : key.getKey().split("/", -1)) {
                if (segment.equals(".") || segment.equals("..") || segment.isEmpty()) {
                    throw new IOException("Invalid registry path: " + entry.getKey());
                }
            }
            if (!(entry.getValue() instanceof ConfigurationSection value)) {
                throw new IOException("Expected a mapping for " + section.getCurrentPath() + "." + entry.getKey());
            }
            files.put(pack.resolve("data").resolve(key.getNamespace()).resolve(kind).resolve(key.getKey() + ".json"),
                    JSON.toJson(plainValues(value)) + "\n");
        }
    }

    private static Map<String, Object> plainValues(ConfigurationSection section) {
        Map<String, Object> values = new LinkedHashMap<>();
        section.getValues(false).forEach((key, value) ->
                values.put(key, value instanceof ConfigurationSection child ? plainValues(child) : value));
        return values;
    }

    // Import the preserved JSON once; later edits belong to the YAML configuration.
    public static void saveConfiguration(Path configuration, Path pack) throws IOException {
        if (Files.exists(configuration)) return;
        Map<String, Object> types = new LinkedHashMap<>();
        Map<String, Object> tags = new LinkedHashMap<>();
        if (Files.isDirectory(pack.resolve("data"))) {
            try (Stream<Path> paths = Files.walk(pack.resolve("data"))) {
                for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                    Path relative = pack.resolve("data").relativize(file);
                    if (!file.getFileName().toString().endsWith(".json") || relative.getNameCount() < 3) continue;
                    String kind = relative.getName(1).toString();
                    int start = kind.equals("damage_type") ? 2
                            : kind.equals("tags") && relative.getNameCount() >= 4
                            && relative.getName(2).toString().equals("damage_type") ? 3 : -1;
                    if (start < 0) continue;
                    String path = relative.subpath(start, relative.getNameCount()).toString().replace('\\', '/');
                    String id = relative.getName(0) + ":" + path.substring(0, path.length() - 5);
                    final Map<?, ?> content;
                    try {
                        content = JSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Map.class);
                    } catch (RuntimeException failure) {
                        throw new IOException("Invalid damage registry JSON " + file, failure);
                    }
                    if (content == null) throw new IOException("Empty damage registry JSON " + file);
                    (start == 2 ? types : tags).put(id, content);
                }
            }
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("damage-types", types);
        yaml.set("tags", tags);
        DatapackSupport.writeIfChanged(configuration, yaml.saveToString());
    }
}
