package com.huidu.farmersdelight.api.item;

import com.huidu.farmersdelight.util.CustomBlockUtils;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.component.DataComponentKeys;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.inventory.ItemStack;

/**
 * Invisible slot filler shared by the container GUIs: a tagged copy of the background item that every code
 * path treats as an empty slot, so it is never taken, consumed or persisted as contents. The marker key is
 * supplied per container rather than fixed here, because two GUIs must never read each other's filler as an
 * occupied slot.
 */
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
