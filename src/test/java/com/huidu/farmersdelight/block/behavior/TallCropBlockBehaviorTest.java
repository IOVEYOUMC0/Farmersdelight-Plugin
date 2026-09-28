package com.huidu.farmersdelight.block.behavior;

import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.property.EnumProperty;
import net.momirealms.craftengine.core.block.property.IntegerProperty;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.block.property.type.DoubleBlockHalf;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.plugin.config.KnownResourceException;
import net.momirealms.craftengine.core.util.Key;
import com.huidu.farmersdelight.util.compat.ProtectionCompat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void appliesRiceFlagToRiceOnly() {
        assertEquals(ProtectionCompat.Feature.RICE,
                TallCropBlockBehavior.protectionFeature(Key.of("farmersdelight:rice")));
        assertNull(TallCropBlockBehavior.protectionFeature(Key.of("corndelight:corn_crop")));
    }

    private static TallCropBlockBehavior crop(String blockId, Map<String, Object> config) {
        Map<String, Object> copy = new LinkedHashMap<>(config);
        return TallCropBlockBehavior.FACTORY.create(block(blockId), ConfigSection.ofRoot(copy));
    }

    // The behavior requires an int 'age' and a double_block_half 'half' property on the block it is
    // attached to, so the stub declares both; a block without them fails to construct by design.
    private static final Property<Integer> AGE = IntegerProperty.create("age", 0, 4, 0);
    private static final Property<DoubleBlockHalf> HALF = EnumProperty.create(
            "half", DoubleBlockHalf.class, List.of(DoubleBlockHalf.LOWER, DoubleBlockHalf.UPPER),
            DoubleBlockHalf.LOWER);

    private static BlockDefinition block(String blockId) {
        Key key = Key.of(blockId);
        return (BlockDefinition) Proxy.newProxyInstance(
                BlockDefinition.class.getClassLoader(),
                new Class<?>[]{BlockDefinition.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "id" -> key;
                    case "getProperty" -> switch (String.valueOf(args[0])) {
                        case "age" -> AGE;
                        case "half" -> HALF;
                        default -> null;
                    };
                    case "toString" -> "TestBlockDefinition[" + key + "]";
                    default -> throw new UnsupportedOperationException(method.toString());
                }
        );
    }

    @Test
    void riceCycleDefaultsKeepTheUpperHalfOnTheMaturityTick() {
        TallCropBlockBehavior rice = crop("farmersdelight:rice", Map.of());

        // The stub's age property tops out at 4, so both bounds come from inference rather than config.
        assertEquals(4, rice.getMaxAgeLower());
        assertEquals(4, rice.getUpperMinAge());
        assertFalse(rice.usesVanillaGrowth());
        assertFalse(rice.carriesBoneMealOverflow());
    }

    @Test
    void readsTheVanillaHighCropCycleSwitches() {
        TallCropBlockBehavior corn = crop("corndelight:corn_crop", Map.of(
                "max-age-lower", 7,
                "max-age-upper", 7,
                "upper-min-age", 4,
                "vanilla-growth", true,
                "bone-meal-overflow", true,
                "reset-on-harvest", false
        ));

        assertEquals(7, corn.getMaxAgeLower());
        assertEquals(7, corn.getMaxAgeUpper());
        assertEquals(4, corn.getUpperMinAge());
        assertTrue(corn.usesVanillaGrowth());
        assertTrue(corn.carriesBoneMealOverflow());
        assertFalse(corn.resetsOnHarvest());
    }

    @Test
    void upperMinAgeDefaultsToTheLowerHalfMaturity() {
        TallCropBlockBehavior crop = crop("corndelight:corn_crop", Map.of("max-age-lower", 7));

        assertEquals(7, crop.getUpperMinAge());
    }

    @Test
    void missingAgePropertyAbortsBlockLoad() {
        assertThrows(KnownResourceException.class, () -> TallCropBlockBehavior.FACTORY.create(
                block("farmersdelight:rice"),
                ConfigSection.ofRoot(new LinkedHashMap<>(Map.of("age-property", "no_such_property")))));
    }

    @Test
    void missingHalfPropertyAbortsBlockLoad() {
        assertThrows(KnownResourceException.class, () -> TallCropBlockBehavior.FACTORY.create(
                block("farmersdelight:rice"),
                ConfigSection.ofRoot(new LinkedHashMap<>(Map.of("half-property", "no_such_property")))));
    }
}
