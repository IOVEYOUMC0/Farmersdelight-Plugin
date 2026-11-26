package com.huidu.farmersdelight.api.content;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockStateRefTest {

    @Test
    void parsesIdWithoutProperties() {
        BlockStateRef ref = BlockStateRef.parse("farmersdelight:rich_soil");
        assertEquals("farmersdelight:rich_soil", ref.blockId().value());
        assertTrue(ref.properties().isEmpty());
        assertEquals("farmersdelight:rich_soil", ref.value());
    }

    @Test
    void parsesAndCanonicalisesProperties() {
        BlockStateRef ref = BlockStateRef.parse("minecraft:oak_stairs[half=bottom,facing=north]");
        assertEquals("minecraft:oak_stairs", ref.blockId().value());
        assertEquals("bottom", ref.properties().get("half"));
        assertEquals("north", ref.properties().get("facing"));
        assertEquals("minecraft:oak_stairs[facing=north,half=bottom]", ref.value());
    }

    @Test
    void propertyKeysAreCaseInsensitiveAndNormalised() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("FACING", "north");
        properties.put("Half", "bottom");
        BlockStateRef ref = BlockStateRef.of(ContentId.parse("minecraft:oak_stairs"), properties);
        assertEquals("north", ref.properties().get("facing"));
        assertEquals("bottom", ref.properties().get("HALF"));
        assertEquals("minecraft:oak_stairs[facing=north,half=bottom]", ref.value());
    }

    @Test
    void equalStatesIgnorePropertyInsertionOrder() {
        BlockStateRef a = BlockStateRef.parse("minecraft:oak_stairs[facing=north,half=bottom]");
        BlockStateRef b = BlockStateRef.parse("minecraft:oak_stairs[half=bottom,facing=north]");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void rejectsMalformedStates() {
        for (String invalid : new String[]{
                "minecraft:stone[", "minecraft:stone]", "minecraft:stone[]]",
                "minecraft:stone[facing]", "minecraft:stone[=north]",
                "minecraft:stone[facing=]", "minecraft:stone[facing=north,"}) {
            assertThrows(IllegalArgumentException.class, () -> BlockStateRef.parse(invalid), invalid);
        }
        assertThrows(IllegalArgumentException.class, () -> BlockStateRef.parse(null));
    }

    @Test
    void optionalHelperReturnsNullForInvalidInput() {
        assertNull(BlockStateRef.parseOrNull(null));
        assertNull(BlockStateRef.parseOrNull("  "));
        assertNull(BlockStateRef.parseOrNull("minecraft:stone["));
        assertEquals("minecraft:stone", BlockStateRef.parseOrNull("minecraft:stone").value());
    }

    @Test
    void distinctStatesAreNotEqual() {
        assertNotEquals(BlockStateRef.parse("minecraft:stone"),
                BlockStateRef.parse("minecraft:dirt"));
        assertNotEquals(BlockStateRef.parse("minecraft:stone[facing=north]"),
                BlockStateRef.parse("minecraft:stone[facing=south]"));
    }
}
