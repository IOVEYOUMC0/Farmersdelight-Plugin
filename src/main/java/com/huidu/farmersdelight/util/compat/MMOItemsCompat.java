package com.huidu.farmersdelight.util.compat;

import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.component.DataComponentKeys;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.libraries.nbt.Tag;

// Preserve the unambiguous legacy MMOItems identity stored in custom_data. Construction uses CE ItemSource.
public final class MMOItemsCompat {

    private static final String PREFIX = "mmoitems:";
    // Identity keys MMOItems writes into the minecraft:custom_data component.
    private static final String KEY_ITEM_TYPE = "MMOITEMS_ITEM_TYPE";
    private static final String KEY_ITEM_ID = "MMOITEMS_ITEM_ID";

    private MMOItemsCompat() {
    }

    static String getItemId(Item item) {
        Tag tag = item.getSparrowTag(DataComponentKeys.CUSTOM_DATA);
        if (tag instanceof CompoundTag compound) {
            String type = compound.getString(KEY_ITEM_TYPE, "");
            String id = compound.getString(KEY_ITEM_ID, "");
            if (!type.isEmpty() && !id.isEmpty()) return PREFIX + type + ":" + id;
        }
        return null;
    }
}
