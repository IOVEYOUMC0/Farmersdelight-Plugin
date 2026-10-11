package com.huidu.farmersdelight.pack;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks knife and skillet enchantment tag membership.
 * Enchantable components permit table cost calculation; tags restrict the offered enchantments.
 */
class EnchantableTagsTest {

    private static final List<String> KNIVES = List.of(
            "farmersdelight:flint_knife",
            "farmersdelight:iron_knife",
            "farmersdelight:golden_knife",
            "farmersdelight:diamond_knife",
            "farmersdelight:netherite_knife");

    private static final List<String> KNIVES_AND_SKILLET = List.of(
            "farmersdelight:flint_knife",
            "farmersdelight:iron_knife",
            "farmersdelight:golden_knife",
            "farmersdelight:diamond_knife",
            "farmersdelight:netherite_knife",
            "farmersdelight:skillet");

    /** Expected enchantment tags and their members. */
    private static final Map<String, List<String>> EXPECTED = Map.of(
            "minecraft:enchantable/durability", KNIVES_AND_SKILLET,
            "minecraft:enchantable/weapon", KNIVES_AND_SKILLET,
            "minecraft:enchantable/sharp_weapon", KNIVES_AND_SKILLET,
            "minecraft:enchantable/fire_aspect", KNIVES_AND_SKILLET,
            "minecraft:enchantable/sword", KNIVES_AND_SKILLET,
            "minecraft:enchantable/mining", KNIVES,
            "minecraft:enchantable/mining_loot", KNIVES);

    @Test
    void everyTagCarriesExactlyTheKnivesAndTheSkillet() throws IOException {
        String yaml = tags();
        for (Map.Entry<String, List<String>> entry : EXPECTED.entrySet()) {
            List<String> members = membersOf(yaml, entry.getKey());
            assertEquals(entry.getValue(), members,
                    entry.getKey() + " has to list exactly " + entry.getValue()
                            + ", in order and without extras");
        }
    }

    private static List<String> membersOf(String yaml, String tag) {
        int start = yaml.indexOf("\"" + tag + "\":");
        assertTrue(start >= 0, tag + " has to be declared in common-tags.yml");
        List<String> members = new ArrayList<>();
        for (String line : yaml.substring(start).split("\n")) {
            String trimmed = line.trim();
            if (trimmed.equals("\"" + tag + "\":")) {
                continue;
            }
            if (!trimmed.startsWith("- ")) {
                if (!members.isEmpty()) {
                    break;
                }
                continue;
            }
            members.add(trimmed.substring(2).replace("\"", "").trim());
        }
        return members;
    }

    private static String tags() throws IOException {
        String path = "/common-tags.yml";
        try (InputStream in = EnchantableTagsTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "common-tags.yml has to be on the test classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
