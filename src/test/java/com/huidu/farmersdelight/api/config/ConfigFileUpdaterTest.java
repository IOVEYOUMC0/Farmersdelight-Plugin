package com.huidu.farmersdelight.api.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigFileUpdaterTest {

    @Test
    void copiesOneLegacySectionToMultipleMissingTargetsWithoutRemovingTheSource() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("enchantments.table.enabled", false);
        config.set("enchantments.table.enchantments", List.of("minecraft:fortune"));

        assertTrue(ConfigFileUpdater.copyPathIfMissing(
                config,
                "enchantments.table",
                "enchantments.groups.knives.table"
        ));
        assertTrue(ConfigFileUpdater.copyPathIfMissing(
                config,
                "enchantments.table",
                "enchantments.groups.skillet.table"
        ));
        assertFalse(ConfigFileUpdater.copyPathIfMissing(
                config,
                "enchantments.table",
                "enchantments.groups.knives.table"
        ));

        assertFalse(config.getBoolean("enchantments.groups.knives.table.enabled"));
        assertFalse(config.getBoolean("enchantments.groups.skillet.table.enabled"));
        assertEquals(
                List.of("minecraft:fortune"),
                config.getStringList("enchantments.groups.skillet.table.enchantments")
        );
        assertTrue(config.isSet("enchantments.table"));
    }
}
