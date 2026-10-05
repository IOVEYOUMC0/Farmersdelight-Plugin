package com.huidu.farmersdelight.api.util;

import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import net.momirealms.craftengine.core.item.Item;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Conversion between CraftEngine's Item and Bukkit's ItemStack.
 *
 *
 * These are the conversions the plugin's container and recipe code perform on every stored item, so each
 * one lives here instead of being copied per call site. Nothing here resolves CraftEngine on its own: the
 * conversion is a direct pass-through of CraftEngine's own ItemStackUtils.getBukkitStack, so a
 * caller that can run before CraftEngine is ready keeps its own readiness guard, and a caller that needs a
 * substitute for a failed conversion keeps that substitute.
 */
@ApiStatus.NonExtendable
public final class CeItemInterop {

    private CeItemInterop() {
    }

    /**
     * The Bukkit stack behind a CraftEngine item, or null when there is nothing to convert.
     *
     *
     * A null or empty item returns null; the count is not inspected. CraftEngine is not consulted for
     * readiness, so a failure inside the conversion propagates to the caller.
     */
    public static ItemStack asBukkitStack(Item item) {
        if (item == null || item.isEmpty()) {
            return null;
        }
        return toBukkitStack(item);
    }

    /**
     * The Bukkit stack behind a CraftEngine item, without the empty-item guard, for a caller that inspects
     * the converted stack itself or substitutes its own fallback.
     *
     *
     * A null item returns null; every other item is handed to CraftEngine's conversion unchanged.
     */
    public static ItemStack toBukkitStack(Item item) {
        if (item == null) {
            return null;
        }
        return ItemStackUtils.getBukkitStack(item.minecraftItem());
    }

    /**
     * The same item as a defensive copy, or Item#empty() when there is nothing usable to copy: a
     * null item, an empty item, or one whose count is not positive.
     */
    public static Item normalize(Item item) {
        if (item == null || item.isEmpty() || item.count() <= 0) {
            return Item.empty();
        }
        return item.copyWithCount(item.count());
    }
}
