package com.huidu.farmersdelight.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrawDropConfigTest {

    @Test
    void aMaterialRuleAnswersForTheConfiguredBlockType() throws InvalidConfigurationException {
        StrawDropConfig config = load("sugar_cane: true");

        assertTrue(config.hasRule(Material.SUGAR_CANE));
        assertFalse(config.hasRule(Material.WHEAT));
        assertFalse(config.hasRule((Material) null));
    }

    @Test
    void aLaterLoadCanAddAMaterialRule() throws InvalidConfigurationException {
        StrawDropConfig config = load("sugar_cane: true");
        assertFalse(config.hasRule(Material.BAMBOO));

        config.loadFromConfig(section("bamboo: true"));

        assertTrue(config.hasRule(Material.BAMBOO), "a rule added by a later load must not stay cached as absent");
        assertTrue(config.hasRule(Material.SUGAR_CANE), "rules only accumulate");
    }

    private static StrawDropConfig load(String yaml) throws InvalidConfigurationException {
        StrawDropConfig config = new StrawDropConfig();
        config.loadFromConfig(section(yaml));
        return config;
    }

    private static ConfigurationSection section(String yaml) throws InvalidConfigurationException {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.loadFromString(yaml);
        return configuration;
    }
}
