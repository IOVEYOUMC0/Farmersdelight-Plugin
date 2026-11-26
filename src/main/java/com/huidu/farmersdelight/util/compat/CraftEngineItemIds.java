package com.huidu.farmersdelight.util.compat;

import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/** CraftEngine item-id bridge kept out of the addon-facing neutral API. */
public final class CraftEngineItemIds {

    private CraftEngineItemIds() {
    }

    @Nullable
    public static String customItemId(ItemStack item) {
        Key id = CraftEngineItems.getCustomItemId(item);
        return id == null ? null : id.toString();
    }
}
