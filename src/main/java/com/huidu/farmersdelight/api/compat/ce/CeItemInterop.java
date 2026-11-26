package com.huidu.farmersdelight.api.compat.ce;

import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import net.momirealms.craftengine.core.item.Item;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/** Conversion between CraftEngine's Item and Bukkit's ItemStack; CE-pinned. */
@ApiStatus.Experimental
public final class CeItemInterop {

    private CeItemInterop() {
    }

    public static ItemStack asBukkitStack(Item item) {
        if (item == null || item.isEmpty()) {
            return null;
        }
        return toBukkitStack(item);
    }

    public static ItemStack toBukkitStack(Item item) {
        if (item == null) {
            return null;
        }
        return ItemStackUtils.getBukkitStack(item.minecraftItem());
    }

    public static Item normalize(Item item) {
        if (item == null || item.isEmpty() || item.count() <= 0) {
            return Item.empty();
        }
        return item.copyWithCount(item.count());
    }
}
