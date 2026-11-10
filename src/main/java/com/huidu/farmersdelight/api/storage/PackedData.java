package com.huidu.farmersdelight.api.storage;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * Mutable typed tree for plugin-owned data packed into an item. Values are limited to strings, integers,
 * doubles, booleans, ItemStacks and nested trees; instances are not thread-safe.
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
