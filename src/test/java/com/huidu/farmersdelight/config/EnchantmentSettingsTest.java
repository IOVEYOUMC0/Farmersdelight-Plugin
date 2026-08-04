package com.huidu.farmersdelight.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Set;

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
        assertTrue(settings.anvil().enchantments().contains("minecraft:mending"));
        assertEquals(1.4D, settings.backstabbing().combat().multiplier(1), 0.0001D);
        assertEquals(1.8D, settings.backstabbing().combat().multiplier(3), 0.0001D);
    }

    @Test
    void parsesAndClampsOperatorConfiguration() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                enchantments:
                  compatibility:
                    auto-disable-on-conflict: false
                    plugins: [CustomEnchants]
                  table:
                    override-offers: false
                    append-chance: 4.0
                    default-enchantability: 0
                    item-enchantability:
                      test:knife: 2000
                    enchantments:
                      - SHARPNESS
                      - minecraft:sharpness
                      - invalid id
                  anvil:
                    conflict-penalty: -2
                    minimum-repair-cost: 50000
                    enchantments: []
                  backstabbing:
                    id: test:../escape
                    definition:
                      max-level: 999
                      slots: [mainhand, offhand]
                      supported-items: [minecraft:iron_sword]
                    combat:
                      behind-dot-threshold: -9
                      sound-location: attacker
                      sound-pitch: 9
                  datapack:
                    directory: ../unsafe
                    pack-format: 0
                """);

        EnchantmentSettings settings = EnchantmentSettings.load(
                yaml.getConfigurationSection("enchantments"));

        assertFalse(settings.conflict().autoDisableOnConflict());
        assertEquals(java.util.List.of("CustomEnchants"), settings.conflict().plugins());
        assertFalse(settings.table().overrideOffers());
        assertEquals(1.0D, settings.table().appendChance(), 0.0001D);
        assertEquals(1, settings.table().defaultEnchantability());
        assertEquals(1024, settings.table().enchantabilityFor(Set.of("test:knife")));
        assertEquals(java.util.List.of("minecraft:sharpness"), settings.table().enchantments());
        assertEquals(0, settings.anvil().conflictPenalty());
        assertEquals(32767, settings.anvil().minimumRepairCost());
        assertTrue(settings.anvil().enchantments().isEmpty());
        assertEquals("farmersdelight:backstabbing", settings.backstabbing().id());
        assertEquals(255, settings.backstabbing().definition().maxLevel());
        assertEquals(java.util.List.of("mainhand", "offhand"), settings.backstabbing().definition().slots());
        assertEquals(-1.0D, settings.backstabbing().combat().behindDotThreshold(), 0.0001D);
        assertEquals(EnchantmentSettings.SoundLocation.ATTACKER,
                settings.backstabbing().combat().soundLocation());
        assertEquals(2.0F, settings.backstabbing().combat().soundPitch(), 0.0001F);
        assertEquals("farmersdelight_enchant", settings.datapack().directory());
        assertEquals(1, settings.datapack().packFormat());
    }
}
