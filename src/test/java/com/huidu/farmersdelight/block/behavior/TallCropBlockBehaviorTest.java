package com.huidu.farmersdelight.block.behavior;

import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TallCropBlockBehaviorTest {

    @AfterEach
    void cleanup() {
        TallCropBlockBehavior.cleanupAll();
    }

    @Test
    void mapsExtraPlantingItemToCropBlockBehavior() {
        TallCropBlockBehavior rice = crop("farmersdelight:rice", Map.of(
                "extra_planting_items", List.of("farmersdelight:rice_panicle")
        ));

        assertTrue(rice.extraPlantingItems().contains(Key.of("farmersdelight:rice_panicle")));
        assertEquals(
                Key.of("farmersdelight:rice"),
                TallCropBlockBehavior.getExtraPlantingCrop(Key.of("farmersdelight:rice_panicle"))
        );
    }

    @Test
    void duplicateExtraPlantingItemDoesNotOverwriteFirstCrop() {
        crop("farmersdelight:rice", Map.of(
                "extra_planting_items", List.of("farmersdelight:shared_seed")
        ));
        crop("farmersdelight:cabbages", Map.of(
                "extra_planting_items", List.of("farmersdelight:shared_seed")
        ));

        assertEquals(
                Key.of("farmersdelight:rice"),
                TallCropBlockBehavior.getExtraPlantingCrop(Key.of("farmersdelight:shared_seed"))
        );
    }

    @Test
    void acceptsHyphenatedExtraPlantingItemsKey() {
        crop("farmersdelight:rice", Map.of(
                "extra-planting-items", "farmersdelight:rice_panicle"
        ));

        assertEquals(
                Key.of("farmersdelight:rice"),
                TallCropBlockBehavior.getExtraPlantingCrop(Key.of("farmersdelight:rice_panicle"))
        );
    }

    @Test
    void reloadingSameCropRemovesOldExtraPlantingItems() {
        crop("farmersdelight:rice", Map.of(
                "extra_planting_items", List.of("farmersdelight:old_seed")
        ));
        crop("farmersdelight:rice", Map.of(
                "extra_planting_items", List.of("farmersdelight:new_seed")
        ));

        assertNull(TallCropBlockBehavior.getExtraPlantingCrop(Key.of("farmersdelight:old_seed")));
        assertEquals(
                Key.of("farmersdelight:rice"),
                TallCropBlockBehavior.getExtraPlantingCrop(Key.of("farmersdelight:new_seed"))
        );
    }

    private static TallCropBlockBehavior crop(String blockId, Map<String, Object> config) {
        Map<String, Object> copy = new LinkedHashMap<>(config);
        return TallCropBlockBehavior.FACTORY.create(block(blockId), ConfigSection.ofRoot(copy));
    }

    private static BlockDefinition block(String blockId) {
        Key key = Key.of(blockId);
        return (BlockDefinition) Proxy.newProxyInstance(
                BlockDefinition.class.getClassLoader(),
                new Class<?>[]{BlockDefinition.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "id" -> key;
                    case "getProperty" -> null;
                    case "toString" -> "TestBlockDefinition[" + key + "]";
                    default -> throw new UnsupportedOperationException(method.toString());
                }
        );
    }
}
