package com.huidu.farmersdelight.api.util;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DamageTypePackTest {
    @TempDir Path directory;

    @Test
    void configurationChangesRegenerateFilesAndPruneRemovedDefinitions() throws Exception {
        Path config = directory.resolve("damage-types.yml");
        Path pack = directory.resolve("pack");
        Files.writeString(config, "damage-types:\n  test:burn:\n    exhaustion: 0.1\n    message_id: test.burn\n    scaling: never\ntags:\n  minecraft:is_fire:\n    replace: false\n    values: [test:burn]\n");
        assertTrue(DamageTypePack.prepare(config, null, pack, "test", "26.2"));
        assertFalse(DamageTypePack.prepare(config, null, pack, "test", "26.2"));
        Files.writeString(config, "damage-types:\n  test:other:\n    exhaustion: 0.3\n    message_id: test.other\n    scaling: never\n");
        assertTrue(DamageTypePack.prepare(config, null, pack, "test", "26.2"));
        assertFalse(Files.exists(pack.resolve("data/test/damage_type/burn.json")));
        assertFalse(Files.exists(pack.resolve("data/minecraft/tags/damage_type/is_fire.json")));
        assertEquals(0.3, JsonParser.parseString(Files.readString(pack.resolve("data/test/damage_type/other.json")))
                .getAsJsonObject().get("exhaustion").getAsDouble());
    }

    @Test
    void importsEditedLegacyDefinitionsIntoYamlOnce() throws Exception {
        Path pack = directory.resolve("pack");
        Path definition = pack.resolve("data/test/damage_type/nested/burn.json");
        Files.createDirectories(definition.getParent());
        Files.writeString(definition, "{\"message_id\":\"legacy.custom\",\"exhaustion\":0.7,\"scaling\":\"never\"}");
        Path config = directory.resolve("damage-types.yml");
        DamageTypePack.saveConfiguration(config, pack);
        String saved = Files.readString(config);
        assertTrue(saved.contains("test:nested/burn"));
        assertTrue(saved.contains("legacy.custom"));
        DamageTypePack.prepare(config, null, pack, "test", "1.21.8");
        assertEquals("legacy.custom", JsonParser.parseString(Files.readString(definition))
                .getAsJsonObject().get("message_id").getAsString());
        Files.writeString(definition, "{}");
        DamageTypePack.saveConfiguration(config, pack);
        assertEquals(saved, Files.readString(config));
    }

    @Test
    void invalidYamlAndEscapingIdsLeaveExistingPackUntouched() throws Exception {
        Path config = directory.resolve("damage-types.yml");
        Path pack = directory.resolve("pack");
        Files.createDirectories(pack);
        Files.writeString(pack.resolve("pack.mcmeta"), "preserve");
        Files.writeString(config, "damage-types: [\n");
        assertThrows(IOException.class, () -> DamageTypePack.prepare(config, null, pack, "test", "26.2"));
        Files.writeString(config, "tags: {}\n");
        assertThrows(IOException.class, () -> DamageTypePack.prepare(config, null, pack, "test", "26.2"));
        Files.writeString(config, "damage-types: [invalid]\n");
        assertThrows(IOException.class, () -> DamageTypePack.prepare(config, null, pack, "test", "26.2"));
        Files.writeString(config, "damage-types:\n  test:../escape:\n    exhaustion: 0\n");
        assertThrows(IOException.class, () -> DamageTypePack.prepare(config, null, pack, "test", "26.2"));
        assertEquals("preserve", Files.readString(pack.resolve("pack.mcmeta")));
        assertFalse(Files.exists(directory.resolve("escape.json")));
    }

    @Test
    void invalidLegacyJsonDoesNotWriteConfiguration() throws Exception {
        Path pack = directory.resolve("pack");
        Path file = pack.resolve("data/test/damage_type/burn.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "broken");
        Path config = directory.resolve("damage-types.yml");
        assertThrows(IOException.class, () -> DamageTypePack.saveConfiguration(config, pack));
        assertFalse(Files.exists(config));
        assertEquals("broken", Files.readString(file));
    }

    @Test
    void bundledDefaultsDoNotWriteUserConfigurationDuringBootstrap() throws Exception {
        Path config = directory.resolve("damage-types.yml");
        var defaults = new ByteArrayInputStream("damage-types:\n  test:burn:\n    exhaustion: 0.1\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(DamageTypePack.prepare(config, defaults, directory.resolve("pack"), "test", "26.2"));
        assertFalse(Files.exists(config));
    }
}
