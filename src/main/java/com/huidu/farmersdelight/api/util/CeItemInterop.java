package com.huidu.farmersdelight.api.util;

import net.momirealms.craftengine.core.item.Item;
import org.bukkit.inventory.ItemStack;

/**
 * Compatibility delegate for {@link com.huidu.farmersdelight.api.compat.ce.CeItemInterop}.
 *
 * @deprecated use the CraftEngine-pinned {@code api.compat.ce} class for new code.
 */
@Deprecated
public final class CeItemInterop {

    private CeItemInterop() {
    }

    public static ItemStack asBukkitStack(Item item) {
        return com.huidu.farmersdelight.api.compat.ce.CeItemInterop.asBukkitStack(item);
    }

    public static ItemStack toBukkitStack(Item item) {
        return com.huidu.farmersdelight.api.compat.ce.CeItemInterop.toBukkitStack(item);
    }

    public static Item normalize(Item item) {
        return com.huidu.farmersdelight.api.compat.ce.CeItemInterop.normalize(item);
    }
}
