package com.huidu.farmersdelight.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KnifeSettingsTest {
    @Test
    void normalizesIdsAndUsesDefaultKnifeTag() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("knife-items.items", java.util.List.of(" Minecraft:Iron_Hoe ", ""));

        KnifeSettings settings = KnifeSettings.load(yaml);

        assertEquals(java.util.Set.of("minecraft:iron_hoe"), settings.itemIds());
        assertEquals(java.util.Set.of("farmersdelight:tools/knives"), settings.tagIds());
    }
}
