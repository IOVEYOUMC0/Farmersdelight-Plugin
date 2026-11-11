package com.huidu.farmersdelight.api.storage;

import com.huidu.farmersdelight.storage.PackedDataCodec;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Reads and writes plugin-owned trees under one key of an item's {@code minecraft:custom_data}. Writes merge
 * into the existing component; {@link #remove} deletes only the caller's key. Carrier items must stay
 * single-stack, and this is a pure item transform (no world, block entity or inventory access).
 *
 * <p>Values must come from {@link #create()} or {@link #read}; implementations are not accepted. The write
 * applies the component through CraftEngine, so pass a CraftEngine-built stack (a CraftItemStack); a plain
 * {@code new ItemStack(...)} is copied during the write and the caller's stack stays unchanged. The api jar
 * cannot execute this class on its own: the FarmersDelight plugin has to be present at runtime.
 */
@ApiStatus.AvailableSince("6")
public final class PackedStorageApi {

    private PackedStorageApi() {
    }

    /** A new empty tree. */
    public static PackedData create() {
        return PackedDataCodec.create();
    }

    /** The tree stored under {@code key}, or null when the item carries none. */
    @Nullable
    public static PackedData read(ItemStack item, String key) {
        return PackedDataCodec.read(item, key);
    }

    /** Stores {@code data} under {@code key}, preserving every other key of the custom data component. */
    public static void write(ItemStack item, String key, PackedData data) {
        PackedDataCodec.write(item, key, data);
    }

    /** Removes the caller's key; every other key of the custom data component is preserved. */
    public static void remove(ItemStack item, String key) {
        PackedDataCodec.remove(item, key);
    }
}
