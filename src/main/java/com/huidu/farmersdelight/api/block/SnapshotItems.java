package com.huidu.farmersdelight.api.block;

import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Cloning helpers shared by the station snapshot records. Every ItemStack that crosses the api
 * boundary is copied on the way in and on the way out, so a caller can never reach a live stack held
 * by a station's block entity and a caller mutating what it was handed can never corrupt a station.
 */
final class SnapshotItems {

    private SnapshotItems() {
    }

    static ItemStack copy(ItemStack item) {
        return ItemUtils.cloneOrNull(item);
    }

    /** An unmodifiable list of clones; null entries are preserved so slot indexes stay meaningful. */
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
