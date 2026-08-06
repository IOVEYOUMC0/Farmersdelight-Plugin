package com.huidu.farmersdelight.api.block;

import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class SnapshotItems {

    private SnapshotItems() {
    }

    static ItemStack copy(ItemStack item) {
        return ItemUtils.cloneOrNull(item);
    }

    static List<ItemStack> copyList(List<ItemStack> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<ItemStack> copies = new ArrayList<>(items.size());
        for (ItemStack item : items) {
            copies.add(copy(item));
        }
        return Collections.unmodifiableList(copies);
    }

    static List<Integer> copyInts(List<Integer> values) {
        return values == null || values.isEmpty() ? List.of() : List.copyOf(values);
    }
}
