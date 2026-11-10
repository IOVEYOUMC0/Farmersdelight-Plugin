package com.huidu.farmersdelight.api.storage;

import com.huidu.farmersdelight.storage.PackedDataCodec;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Packs plugin-owned block state into an item's {@code minecraft:custom_data}.
 *
 * <p>Each plugin owns one {@link org.bukkit.NamespacedKey}-like string key (for example
 * {@code brewinandchewin:keg}) under which its tree is stored. The write path merges into the existing custom
 * data, so CraftEngine item identity, resource-pack fields and other plugins' keys survive; {@link #remove}
 * deletes only the caller's key.
 *
 * <p>Requirements for a packed carrier item: it must be single-stack
 * ({@code minecraft:max_stack_size: 1}), otherwise two items with different packed contents can merge and
 * duplicate what they carry. The API does not change stack size.
 *
 * <p>This is a pure item transform: it never touches the world, a block entity or an inventory, so it is safe
 * wherever item metadata is being built. Keep it on the owning region thread when the item being written is a
 * live block's item. Values the tree cannot represent (lists, raw NBT handles) are skipped on write; only the
 * caller's own key is managed, so a foreign payload is never rewritten.
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
