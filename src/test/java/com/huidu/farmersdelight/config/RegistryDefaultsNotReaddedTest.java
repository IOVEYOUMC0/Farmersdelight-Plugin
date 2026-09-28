package com.huidu.farmersdelight.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The straw and container-return tables are registry sections: their defaults ship in the bundled YAML, and an
 * entry the operator removed must not come back from a code-side default table.
 */
class RegistryDefaultsNotReaddedTest {

    @Test
    void emptiedStrawSectionHasNoRules() {
        StrawDropConfig config = new StrawDropConfig();
        config.loadFromConfig(new YamlConfiguration().createSection("straw"));

        assertFalse(config.hasRule("short_grass"), "an emptied straw section must stay empty");
    }

    @Test
    void missingStrawSectionHasNoRules() {
        StrawDropConfig config = new StrawDropConfig();
        config.loadFromConfig(null);

        assertFalse(config.hasRule("short_grass"), "no section means no rules, not the bundled defaults");
    }

    @Test
    void listedStrawKeyIsRecognisedRegardlessOfCase() {
        StrawDropConfig config = new StrawDropConfig();
        ConfigurationSection section = new YamlConfiguration().createSection("straw");
        section.createSection("Short_Grass");
        config.loadFromConfig(section);

        assertTrue(config.hasRule("short_grass"), "a listed key enables the rule");
        assertFalse(config.hasRule("mature_rice"), "keys that are not listed stay disabled");
    }

    @Test
    void emptyContainerReturnSectionYieldsNoEntries() {
        ContainerReturnConfig config = new ContainerReturnConfig();
        config.loadFromConfig(new YamlConfiguration().createSection("container-returns"));

        assertTrue(config.getContainerReturnMap().isEmpty(),
                "an emptied container-returns section must stay empty");
    }

    @Test
    void missingContainerReturnSectionYieldsNoEntries() {
        ContainerReturnConfig config = new ContainerReturnConfig();
        config.loadFromConfig(null);

        assertTrue(config.getContainerReturnMap().isEmpty(),
                "no section means no return mapping, not the bundled default");
    }
}
