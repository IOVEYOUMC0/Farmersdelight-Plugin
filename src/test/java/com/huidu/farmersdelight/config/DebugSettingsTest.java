package com.huidu.farmersdelight.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugSettingsTest {
    @Test
    void normalizesCategories() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("debug.enabled", true);
        yaml.set("debug.categories", List.of(" GUI ", "", "STOVE"));

        DebugSettings settings = DebugSettings.load(yaml);

        assertTrue(settings.enabled());
        assertEquals(Set.of("gui", "stove"), settings.categories());
    }

    @Test
    void supportsLegacyBooleanSwitch() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("debug", true);

        assertTrue(DebugSettings.load(yaml).enabled());
    }
}
