package com.huidu.farmersdelight.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatapackWorldWhitelistTest {

    @Test
    void bundledConfigDefaultsToThePrimaryWorldOnly() {
        Path path = Path.of("src", "main", "resources", "config.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(path.toFile());

        assertEquals(List.of("$primary"), yaml.getStringList("datapacks.world-whitelist"));
    }

    @Test
    void primaryTokenResolvesToTheConfiguredLevelName() {
        DatapackWorldWhitelist whitelist = DatapackWorldWhitelist.from(List.of("$primary"), "Survival");

        assertTrue(whitelist.allows("survival"));
        assertTrue(whitelist.allows("SURVIVAL"));
        assertFalse(whitelist.allows("survival_nether"));
        assertFalse(whitelist.allows("dungeon_1"));
    }

    @Test
    void explicitWorldNamesAreTrimmedAndMatchedExactly() {
        DatapackWorldWhitelist whitelist = DatapackWorldWhitelist.from(
                List.of(" resource ", "minigame-1"), "world");

        assertEquals(java.util.Set.of("resource", "minigame-1"), whitelist.worldNames());
        assertTrue(whitelist.allows("resource"));
        assertFalse(whitelist.allows("resource_nether"));
        assertFalse(whitelist.allows("world"));
    }

    @Test
    void wildcardRestoresAllWorldInstallation() {
        DatapackWorldWhitelist whitelist = DatapackWorldWhitelist.from(List.of("*"), "world");

        assertTrue(whitelist.allowsAllWorlds());
        assertTrue(whitelist.allows("newly-created-world"));
    }

    @Test
    void emptyWhitelistDisablesDatapackWrites() {
        DatapackWorldWhitelist whitelist = DatapackWorldWhitelist.from(List.of(), "world");

        assertFalse(whitelist.allows("world"));
    }
}
