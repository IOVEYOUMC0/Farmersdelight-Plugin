package com.huidu.farmersdelight.migration;

import com.huidu.farmersdelight.api.migration.LegacyIdMigration;
import net.momirealms.craftengine.core.util.Key;

/**
 * Registers renamed item IDs while retaining their legacy pack definitions.
 * Existing stacks need those definitions until migration recognizes and replaces them.
 * Recipe variant names are not item IDs. Basket migration covers stacks only;
 * placed legacy baskets retain their block definitions and continue to work.
 */
public final class FarmersDelightLegacyIds {

    private FarmersDelightLegacyIds() {
    }

    /** Registers this plugin's own renamed ids; safe to call more than once (duplicates are ignored). */
    public static void register() {
        // 1.4 replaced the barbecue stick with the cooked meat skewer ...
        LegacyIdMigration.registerItem(
                Key.of("farmersdelight:barbecue_stick"),
                Key.of("farmersdelight:cooked_meat_skewer"));
        // ... and renamed the basket to the bamboo basket, adding a wooden basket next to it.
        LegacyIdMigration.registerItem(
                Key.of("farmersdelight:basket"),
                Key.of("farmersdelight:bamboo_basket"));
    }
}
