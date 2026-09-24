package com.huidu.farmersdelight.listener;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The cooking pot tooltip draws a meal's icon by asking its own pack for <namespace>:meal_<item>, so the
 * id derivation has to keep the item's namespace and reject ids that cannot name an image.
 */
class MealGlyphIdTest {

    @Test
    void craftEngineMealKeepsItsOwnNamespace() {
        assertEquals("farmersdelight:meal_beef_stew", BlockBreakListener.mealGlyphId("farmersdelight:beef_stew", null));
        assertEquals("crabbersdelight:meal_seafood_gumbo",
                BlockBreakListener.mealGlyphId("crabbersdelight:seafood_gumbo", "minecraft:bowl"));
        assertEquals("endsdelight:meal_bubble_tea", BlockBreakListener.mealGlyphId("endsdelight:bubble_tea", null));
    }

    @Test
    void vanillaMealUsesThePluginsOwnNamespace() {
        assertEquals("farmersdelight:meal_beetroot_soup",
                BlockBreakListener.mealGlyphId(null, "minecraft:beetroot_soup"));
        assertEquals("farmersdelight:meal_mushroom_stew",
                BlockBreakListener.mealGlyphId(null, "minecraft:mushroom_stew"));
    }

    @Test
    void unusableIdsResolveToNoIcon() {
        assertNull(BlockBreakListener.mealGlyphId(null, null));
        assertNull(BlockBreakListener.mealGlyphId("", ""));
        // MMOItems ids carry two colons and have no CE image behind them.
        assertNull(BlockBreakListener.mealGlyphId("mmoitems:FOOD:TEST", null));
        assertNull(BlockBreakListener.mealGlyphId("farmersdelight:", null));
        assertNull(BlockBreakListener.mealGlyphId("beef_stew", null));
        assertNull(BlockBreakListener.mealGlyphId(null, "not_an_item_id"));
    }
}
