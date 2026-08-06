package com.huidu.farmersdelight.util;

import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.component.DataComponentKeys;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.inventory.ItemStack;

public final class CookingPotPlaceholder {

    private static final String KEY = "farmersdelight_placeholder";

    private CookingPotPlaceholder() {
    }

    public static ItemStack mark(ItemStack base) {
        if (base == null || base.getType().isAir()) {
            return null;
        }
        ItemStack item = base.clone();
        Item wrapped = BukkitItemManager.instance().wrap(item);
        CompoundTag customData = CustomBlockUtils.getComponentCompound(wrapped, DataComponentKeys.CUSTOM_DATA);
        if (customData == null) {
            customData = new CompoundTag();
        }
        customData.putByte(KEY, (byte) 1);
        wrapped.setSparrowTagComponent(DataComponentKeys.CUSTOM_DATA, customData);
        return item;
    }

    public static boolean is(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        Item wrapped = BukkitItemManager.instance().wrap(item);
        CompoundTag customData = CustomBlockUtils.getComponentCompound(wrapped, DataComponentKeys.CUSTOM_DATA);
        return customData != null && customData.get(KEY) != null;
    }

    public static boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir() || is(item);
    }
}
