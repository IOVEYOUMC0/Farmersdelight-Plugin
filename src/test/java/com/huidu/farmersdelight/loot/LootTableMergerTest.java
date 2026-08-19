package com.huidu.farmersdelight.loot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LootTableMergerTest {

    @Test
    void mergeKeepsVanillaPoolsAndAppendsFdPools() {
        String vanilla = """
                {"type":"minecraft:chest","pools":[{"bonus_rolls":0.0,"entries":[\
                {"type":"minecraft:item","name":"minecraft:music_disc_13","weight":15},\
                {"type":"minecraft:item","name":"minecraft:music_disc_cat","weight":15}],"rolls":1.0}],\
                "random_sequence":"minecraft:chests/simple_dungeon"}
                """;
        String append = """
                {"pools":[{"bonus_rolls":0.0,"entries":[\
                {"type":"craftengine:item","name":"farmersdelight:rope"},\
                {"type":"minecraft:empty","weight":2}],"rolls":3.0}]}
                """;
        String merged = LootTableMerger.merge(vanilla, append);
        assertTrue(merged.contains("minecraft:music_disc_13"), "vanilla disc must survive");
        assertTrue(merged.contains("minecraft:music_disc_cat"), "vanilla disc must survive");
        assertTrue(merged.contains("farmersdelight:rope"), "FD pool must be appended");
        assertTrue(merged.contains("minecraft:chests/simple_dungeon"), "random_sequence must survive");
        assertTrue(merged.contains("\"pools\""), "merged output must be a valid table");
        // The merged output must itself be parseable as a loot table by the same merger.
        assertDoesNotThrow(() -> LootTableMerger.merge(merged, "{\"pools\":[]}"));
    }

    @Test
    void filterMissingCeItemsDropsDeletedItems() {
        String append = """
                {"pools":[{"bonus_rolls":0.0,"entries":[\
                {"type":"craftengine:item","name":"farmersdelight:rope"},\
                {"type":"craftengine:item","name":"farmersdelight:deleted_item"},\
                {"type":"minecraft:empty","weight":2}],"rolls":3.0}]}
                """;
        LootTableMerger.FilterResult filtered =
                LootTableMerger.filterMissingCeItems(append, id -> !"farmersdelight:deleted_item".equals(id));
        assertEquals(java.util.List.of("farmersdelight:deleted_item"), filtered.removedItemIds());
        assertFalse(filtered.json().contains("deleted_item"), "deleted entry must be dropped");
        assertTrue(filtered.json().contains("farmersdelight:rope"), "surviving entry must stay");
    }

    @Test
    void filterMissingCeItemsKeepsEverythingWhenPresent() {
        String append = """
                {"pools":[{"bonus_rolls":0.0,"entries":[\
                {"type":"craftengine:item","name":"farmersdelight:rope"}],"rolls":3.0}]}
                """;
        LootTableMerger.FilterResult filtered = LootTableMerger.filterMissingCeItems(append, id -> true);
        assertTrue(filtered.removedItemIds().isEmpty());
        assertEquals(append, filtered.json(), "unchanged append must pass through verbatim");
    }
}
