package com.huidu.farmersdelight.pack;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ensures tool carriers declare the vanilla prerequisites for table enchanting. */
class EnchantablePackItemsTest {

    private static final String[] KNIVES = {
            "farmersdelight:flint_knife",
            "farmersdelight:iron_knife",
            "farmersdelight:golden_knife",
            "farmersdelight:diamond_knife",
            "farmersdelight:netherite_knife",
    };

    @Test
    void everyKnifeDeclaresAVanillaEnchantableComponent() throws IOException {
        String yaml = packFile("knives.yml");
        for (String item : KNIVES) {
            assertItemEnchantable(yaml, item);
        }
    }

    @Test
    void theSkilletDeclaresAVanillaEnchantableComponent() throws IOException {
        assertItemEnchantable(packFile("blocks.yml"), "farmersdelight:skillet");
    }

    @Test
    void everyKnifeDeclaresTheVanillaToolComponentAtItsTierSpeed() throws IOException {
        String yaml = packFile("knives.yml");
        // Vanilla tier speeds used by the mod: flint is its own tier at 4.0, the rest follow their material.
        Map<String, String> expected = Map.of(
                "farmersdelight:flint_knife", "4.0",
                "farmersdelight:iron_knife", "6.0",
                "farmersdelight:golden_knife", "12.0",
                "farmersdelight:diamond_knife", "8.0",
                "farmersdelight:netherite_knife", "9.0");
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            String item = entry.getKey();
            String block = itemBlock(yaml, item);
            assertTrue(block.contains("minecraft:tool:"),
                    item + " must declare minecraft:tool, or it mines the knife-mineable blocks at hand speed");
            assertTrue(block.contains("default_mining_speed: 1.0"),
                    item + " must keep vanilla speed outside the knife-mineable blocks");
            assertTrue(block.contains("damage_per_block: 1"),
                    item + " must consume one durability per broken block like the mod's DiggerItem");
            assertTrue(block.contains("blocks: '#minecraft:wool'"),
                    item + " must declare tag holders as scalar rules, not list members");
            assertTrue(block.contains("correct_for_drops: true"),
                    item + " must be the correct tool for the knife-mineable blocks");
            assertTrue(block.contains("speed: " + entry.getValue()),
                    item + " must mine at the mod's tier speed " + entry.getValue());
        }
    }

    @Test
    void toolRulesDoNotMixTagsWithDirectRegistryHolders() throws IOException {
        org.bukkit.configuration.file.YamlConfiguration yaml =
                new org.bukkit.configuration.file.YamlConfiguration();
        try {
            yaml.loadFromString(packFile("knives.yml"));
        } catch (org.bukkit.configuration.InvalidConfigurationException invalid) {
            throw new AssertionError(invalid);
        }
        for (String id : KNIVES) {
            var tool = yaml.getConfigurationSection("items." + id + ".data.components.minecraft:tool");
            assertNotNull(tool);
            List<Map<?, ?>> rules = tool.getMapList("rules");
            assertEquals(4, rules.size());
            for (Map<?, ?> rule : rules) {
                Object holders = rule.get("blocks");
                if (holders instanceof List<?> list) {
                    assertTrue(list.stream().allMatch(value -> value instanceof String text
                                    && text.startsWith("minecraft:") && !text.startsWith("#")),
                            "Direct holder lists cannot contain #tags or unresolved custom IDs");
                } else {
                    assertTrue(holders instanceof String tag && tag.startsWith("#minecraft:"),
                            "Registry tags must be scalar holder sets");
                }
            }
        }
    }

    private static String itemBlock(String yaml, String item) {
        int itemStart = yaml.indexOf("  " + item + ":");
        assertTrue(itemStart >= 0, item + " has to exist in the pack configuration");
        int nextItem = yaml.indexOf("\n  farmersdelight:", itemStart + 1);
        return nextItem < 0 ? yaml.substring(itemStart) : yaml.substring(itemStart, nextItem);
    }

    private static void assertItemEnchantable(String yaml, String item) {
        int itemStart = yaml.indexOf("  " + item + ":");
        assertTrue(itemStart >= 0, item + " has to exist in the pack configuration");
        int nextItem = yaml.indexOf("\n  farmersdelight:", itemStart + 1);
        String block = nextItem < 0 ? yaml.substring(itemStart) : yaml.substring(itemStart, nextItem);
        int enchantable = block.indexOf("minecraft:enchantable:");
        assertTrue(enchantable >= 0, item + " must declare minecraft:enchantable, or the table's costs stay 0 "
                + "and the options cannot be clicked");
        String tail = block.substring(enchantable);
        List<String> lines = tail.lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
        // The generic CE components codec expects the record form, not a bare integer.
        assertEquals("minecraft:enchantable:", lines.get(0), item + " declares the component");
        assertTrue(lines.get(1).matches("value:\\s*[1-9]\\d*"),
                item + " must declare minecraft:enchantable as a map with a value above zero");
        assertTrue(block.matches("(?s).*(max-stack-size: 1|minecraft:max_stack_size: 1).*"),
                item + " must stay a single-item stack: vanilla only offers enchantments on those");
    }

    private static String packFile(String name) throws IOException {
        String path = "/craftengine/farmersdelight/configuration/" + name;
        try (InputStream in = EnchantablePackItemsTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "pack resource " + path + " has to be on the test classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
