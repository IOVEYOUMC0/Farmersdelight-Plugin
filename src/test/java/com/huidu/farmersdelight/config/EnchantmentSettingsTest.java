package com.huidu.farmersdelight.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnchantmentSettingsTest {

    @Test
    void defaultsPreserveKnifePolicyWithoutHardcodedRuntimeCosts() {
        EnchantmentSettings settings = EnchantmentSettings.defaults();

        assertTrue(settings.enabled());
        assertTrue(settings.table().overrideOffers());
        assertTrue(settings.table().enchantments().contains("minecraft:fortune"));
        assertTrue(settings.table().enchantments().contains("$backstabbing"));
        assertFalse(settings.table().enchantments().contains("minecraft:silk_touch"));
        assertTrue(settings.anvilEnabled());
        assertEquals(14, settings.skillet().table().defaultEnchantability());
        assertFalse(settings.skillet().table().enchantments().contains("$backstabbing"));
        assertFalse(settings.skillet().table().enchantments().contains("minecraft:fortune"));
        assertEquals(1.4D, settings.backstabbing().combat().multiplier(1), 0.0001D);
        assertEquals(1.8D, settings.backstabbing().combat().multiplier(3), 0.0001D);
    }

    @Test
    void parsesAndClampsOperatorConfiguration() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                enchantments:
                  enabled: false
                  compatibility:
                    auto-disable-on-conflict: false
                  table:
                    enabled: false
                    override-offers: false
                    default-enchantability: 0
                    enchantments:
                      - SHARPNESS
                      - minecraft:sharpness
                      - invalid id
                  anvil:
                    enabled: false
                  backstabbing:
                    id: test:../escape
                    definition:
                      weight: 5
                      max-level: 999
                    combat:
                      players-only: false
                      require-knife: false
                      multiplier-base: -9
                      multiplier-per-level: 0
                """);

        EnchantmentSettings settings = EnchantmentSettings.load(
                yaml.getConfigurationSection("enchantments"));

        assertFalse(settings.enabled());
        assertFalse(settings.autoDisableOnConflict());
        assertFalse(settings.table().enabled());
        assertFalse(settings.table().overrideOffers());
        assertEquals(1, settings.table().defaultEnchantability());
        assertEquals(java.util.List.of("minecraft:sharpness"), settings.table().enchantments());
        assertFalse(settings.anvilEnabled());
        assertEquals(settings.knives(), settings.skillet());
        assertEquals("farmersdelight:backstabbing", settings.backstabbing().id());
        assertEquals(255, settings.backstabbing().definition().maxLevel());
        assertEquals(5, settings.backstabbing().definition().weight());
        assertFalse(settings.backstabbing().combat().playersOnly());
        assertFalse(settings.backstabbing().combat().requireKnife());
        assertEquals(0.0D, settings.backstabbing().combat().multiplierBase(), 0.0001D);
        assertEquals(0.0D, settings.backstabbing().combat().multiplierPerLevel(), 0.0001D);
    }

    @Test
    void parsesKnifeAndSkilletGroupsIndependently() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                enchantments:
                  groups:
                    knives:
                      table:
                        default-enchantability: 9
                        enchantments:
                          - minecraft:fortune
                      anvil:
                        enabled: false
                    skillet:
                      table:
                        default-enchantability: 18
                        enchantments:
                          - minecraft:fire_aspect
                      anvil:
                        enabled: true
                """);

        EnchantmentSettings settings = EnchantmentSettings.load(
                yaml.getConfigurationSection("enchantments"));

        assertEquals(9, settings.knives().table().defaultEnchantability());
        assertEquals(java.util.List.of("minecraft:fortune"), settings.knives().table().enchantments());
        assertFalse(settings.knives().anvilEnabled());
        assertEquals(18, settings.skillet().table().defaultEnchantability());
        assertEquals(java.util.List.of("minecraft:fire_aspect"), settings.skillet().table().enchantments());
        assertTrue(settings.skillet().anvilEnabled());
    }
}
