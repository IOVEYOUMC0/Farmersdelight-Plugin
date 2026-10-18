package com.huidu.farmersdelight.pack;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
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
