package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.config.EnchantmentSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnchantmentDatapackInstallerTest {

    @Test
    void rendersRegistryDefinitionFromConfiguration() {
        String json = EnchantmentDatapackInstaller.renderDefinition(
                EnchantmentSettings.defaults().backstabbing());

        assertTrue(json.contains("\"translate\": \"enchantment.farmersdelight.backstabbing\""));
        assertTrue(json.contains("\"weight\": 5"));
        assertTrue(json.contains("\"max_level\": 3"));
        assertTrue(json.contains("\"per_level_above_first\": 9"));
        assertTrue(json.contains("\"slots\": [\"mainhand\"]"));
    }

    @Test
    void emptySupportedItemsTagDoesNotLeakBackstabbingToVanillaSwords() {
        String json = EnchantmentDatapackInstaller.renderSupportedItems(java.util.List.of());

        assertTrue(json.contains("\"replace\": true"));
        assertTrue(json.contains("\"values\": []"));
        assertFalse(json.contains("minecraft:iron_sword"));
    }

    @Test
    void packMetadataEscapesOperatorText() {
        EnchantmentSettings.Datapack datapack = new EnchantmentSettings.Datapack(
                true, "safe", 61, "quoted \"line\"\nnext");
        String json = EnchantmentDatapackInstaller.renderPackMetadata(datapack);

        assertTrue(json.contains("\"pack_format\": 61"));
        assertTrue(json.contains("quoted \\\"line\\\"\\nnext"));
    }
}
