package com.huidu.farmersdelight.api.util;

import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import net.momirealms.craftengine.core.item.Item;
import org.bukkit.inventory.ItemStack;

/**
 * Conversion between CraftEngine's Item and Bukkit's ItemStack.
 */
public final class CeItemInterop {

    private CeItemInterop() {
    }

    /**
     * The Bukkit stack behind a CraftEngine item, or null when there is nothing to convert.
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
