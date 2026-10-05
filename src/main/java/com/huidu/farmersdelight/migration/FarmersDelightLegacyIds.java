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
 */
public final class FarmersDelightLegacyIds {

    private FarmersDelightLegacyIds() {
    }

    /** Registers this plugin's own renamed ids; safe to call more than once (duplicates are ignored). */
    public static void register() {
        LegacyIdMigration.registerItem(
                Key.of("farmersdelight:barbecue_stick"),
                Key.of("farmersdelight:cooked_meat_skewer"));
    }
}
