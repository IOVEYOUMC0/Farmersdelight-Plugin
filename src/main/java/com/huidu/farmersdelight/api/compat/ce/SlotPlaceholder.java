package com.huidu.farmersdelight.api.compat.ce;

import com.huidu.farmersdelight.util.CustomBlockUtils;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.component.DataComponentKeys;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

/**
 * Invisible slot-filler tags backed by CraftEngine item custom data. This is a CE-pinned helper; addons
 * that only need ordinary item recipes should not need it.
 */
@ApiStatus.Experimental
public final class SlotPlaceholder {

    private SlotPlaceholder() {
    }

    /** A tagged invisible copy of base, or null when base is empty. */
    public static ItemStack mark(String key, ItemStack base) {
        if (key == null || base == null || base.getType().isAir()) {
            return null;
        }
        ItemStack item = base.clone();
        Item wrapped = BukkitItemManager.instance().wrap(item);
        CompoundTag customData = CustomBlockUtils.getComponentCompound(wrapped, DataComponentKeys.CUSTOM_DATA);
        if (customData == null) {
            customData = new CompoundTag();
        }
        customData.putByte(key, (byte) 1);
        wrapped.setSparrowTagComponent(DataComponentKeys.CUSTOM_DATA, customData);
        return item;
    }

    public static boolean is(String key, ItemStack item) {
        if (key == null || item == null || item.getType().isAir()) {
            return false;
        }
        Item wrapped = BukkitItemManager.instance().wrap(item);
        CompoundTag customData = CustomBlockUtils.getComponentCompound(wrapped, DataComponentKeys.CUSTOM_DATA);
        return customData != null && customData.get(key) != null;
    }

    /** True if the slot is effectively empty: null, air, or this container's placeholder. */
    public static boolean isEmpty(String key, ItemStack item) {
        return item == null || item.getType().isAir() || is(key, item);
    }
}
