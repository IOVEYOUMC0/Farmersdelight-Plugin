package com.huidu.farmersdelight.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLookupTest {

    @Test
    void usesTheFirstPresentAlias() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("legacy.enabled", false);
        config.set("current.enabled", true);

        assertFalse(ConfigLookup.booleanValue(config, true, "legacy.enabled", "current.enabled"));
        assertEquals(4, ConfigLookup.intValue(config, 4, "missing", "none"));
    }

    @Test
    void ignoresPresentScalarWhenSectionIsRequested() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("legacy", "wrong-shape");
        config.createSection("current").set("enabled", true);

        assertEquals("current", ConfigLookup.firstSection(config, "legacy", "current").getName());
        assertTrue(ConfigLookup.booleanValue(config, false, "current.enabled"));
    }
}
