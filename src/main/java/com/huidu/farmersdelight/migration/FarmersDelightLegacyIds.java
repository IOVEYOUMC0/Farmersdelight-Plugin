package com.huidu.farmersdelight.migration;

import com.huidu.farmersdelight.api.migration.LegacyIdMigration;
import net.momirealms.craftengine.core.util.Key;

/**
 * The ids this plugin renamed in the 1.4 update, handed to {@link LegacyIdMigration}.
 *
 * <p>Upstream 1.4 removed the barbecue stick and kept the cooked meat skewer as its replacement (its
 * {@code RegistryAliases} maps {@code barbecue_stick -> cooked_meat_skewer}). CraftEngine has no alias
 * mechanism, so the pack keeps the old item definition in {@code items.yml} as the migration carrier: with
 * that definition gone, CraftEngine would turn every stack already in the world into an unknown item before
 * the migration could recognise it.
 *
 * <p>The {@code barbecue_stick_*} recipe variants are not item ids and therefore need no mapping: their
 * recipes now craft the 1.4 target instead.
 *
 * <p>The basket rename is registered on the item level only: the migration facility covers item stacks
 * ({@link LegacyIdMigration#registerItem(Key, Key)}), not blocks, so a basket <em>block</em> already placed in
 * an old world keeps its legacy id. Its block definition is retained in the pack for exactly that reason, and
 * the blocks still work; converting them needs the facility to grow a block-level mapping.
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
