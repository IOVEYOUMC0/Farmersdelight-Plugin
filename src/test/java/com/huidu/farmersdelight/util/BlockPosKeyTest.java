package com.huidu.farmersdelight.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockPosKeyTest {

    @Test
    void parsesCommaSeparatedCoordinates() {
        BlockPosKey key = BlockPosKey.fromString("10, 64, -3").orElseThrow();

        assertEquals(10, key.x());
        assertEquals(64, key.y());
        assertEquals(-3, key.z());
        assertEquals("10,64,-3", key.toString());
    }

    @Test
    void rejectsInvalidCoordinateStrings() {
        assertTrue(BlockPosKey.fromString(null).isEmpty());
        assertTrue(BlockPosKey.fromString("").isEmpty());
        assertTrue(BlockPosKey.fromString("1,2").isEmpty());
        assertTrue(BlockPosKey.fromString("1,two,3").isEmpty());
    }

    @Test
    void equalityUsesCoordinatesOnly() {
        assertEquals(new BlockPosKey(1, 2, 3), new BlockPosKey(1, 2, 3));
        assertNotEquals(new BlockPosKey(1, 2, 3), new BlockPosKey(1, 2, 4));
    }
}
