package com.huidu.farmersdelight.api.storage;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * A small typed tree for plugin-owned data packed into an item's {@code minecraft:custom_data}.
 *
 * <p>Values are limited to the shapes a packed block needs — strings, integers, doubles, booleans,
 * {@link ItemStack}s and nested trees — so the payload stays portable across CraftEngine versions and is never a
 * raw NBT handle. Read a tree with {@link PackedStorageApi#read}, edit it, and write it back with
 * {@link PackedStorageApi#write}; the write merges into the item's existing custom data instead of replacing it.
 *
 * <p>Instances are mutable and not thread-safe. Callers own their tree; trees handed out by the API are fresh
 * copies and never shared with the item.
 */
@ApiStatus.AvailableSince("6")
public interface PackedData {

    boolean isEmpty();

    /** The keys currently present, in insertion order. */
    Set<String> keys();

    /** The nested tree under {@code key}, or null when absent or not a tree. */
    @Nullable
    PackedData compound(String key);

    String string(String key, String fallback);

    int integer(String key, int fallback);

    long longValue(String key, long fallback);

    double decimal(String key, double fallback);

    boolean bool(String key, boolean fallback);

    /** A defensive copy of the stack under {@code key}, or null when absent. */
    @Nullable
    ItemStack item(String key);

    PackedData putCompound(String key, PackedData value);

    PackedData putString(String key, String value);

    PackedData putInt(String key, int value);

    PackedData putLong(String key, long value);

    PackedData putDouble(String key, double value);

    PackedData putBool(String key, boolean value);

    PackedData putItem(String key, ItemStack value);

    void remove(String key);
}
