package com.huidu.farmersdelight.listener;

import com.huidu.farmersdelight.api.FarmersDelightApi;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockBreakListenerTest {

    @Test
    void protectsFarmersDelightAndRegisteredAddonBlocksOnly() {
        FarmersDelightApi.get().registerAddonBlockNamespace("testaddon");

        assertTrue(BlockBreakListener.isProtectedBlockId("farmersdelight:rice"));
        assertTrue(BlockBreakListener.isProtectedBlockId("testaddon:crop"));
        assertFalse(BlockBreakListener.isProtectedBlockId("minecraft:wheat"));
        assertFalse(BlockBreakListener.isProtectedBlockId("invalid"));
    }
}
