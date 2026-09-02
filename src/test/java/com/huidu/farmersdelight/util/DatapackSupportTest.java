package com.huidu.farmersdelight.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatapackSupportTest {

    @Test
    void recognizesSharedAndIndependentDatapackRoots() {
        Path primary = Path.of("world", "datapacks", "farmersdelight_enchant");

        assertTrue(DatapackSupport.sameNormalizedPath(primary,
                Path.of("world", "dimensions", "minecraft", "overworld", "..", "..", "..", "datapacks", "farmersdelight_enchant")));
        assertFalse(DatapackSupport.sameNormalizedPath(primary,
                Path.of("world_nether", "datapacks", "farmersdelight_enchant")));
    }
}
