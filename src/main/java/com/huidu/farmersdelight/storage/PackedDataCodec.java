package com.huidu.farmersdelight.storage;

import com.huidu.farmersdelight.api.storage.PackedData;
import com.huidu.farmersdelight.util.ItemUtils;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.component.DataComponentKeys;
import net.momirealms.craftengine.libraries.nbt.ByteTag;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.libraries.nbt.IntTag;
import net.momirealms.craftengine.libraries.nbt.LongTag;
import net.momirealms.craftengine.libraries.nbt.NumericTag;
import net.momirealms.craftengine.libraries.nbt.StringTag;
import net.momirealms.craftengine.libraries.nbt.Tag;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * CraftEngine-backed implementation of {@code api.storage}. Kept out of the api jar: addons see only the
 * api classes and this resolves from the plugin class loader at runtime.
 */
public final class PackedDataCodec {

    private PackedDataCodec() {
    }

    public static PackedData create() {
        return new Tree();
    }

    /** The tree stored under {@code key}, or null when the item carries none. */
    @Nullable
    public static PackedData read(ItemStack item, String key) {
        if (item == null || key == null || key.isBlank()) {
            return null;
        }
        CompoundTag packed = packedCompound(item, key);
        return packed == null ? null : fromTag(packed);
    }

    /** Stores {@code data} under {@code key}, preserving every other key of the custom data component. */
    public static void write(ItemStack item, String key, PackedData data) {
        if (item == null || key == null || key.isBlank() || data == null) {
            return;
        }
        Item wrapped = BukkitItemManager.instance().wrap(item);
        Tag existing = wrapped.getComponentAsSparrowTag(DataComponentKeys.CUSTOM_DATA);
        CompoundTag customData = existing instanceof CompoundTag compound ? compound : new CompoundTag();
        customData.put(key, toTag(data));
        wrapped.setSparrowTagComponent(DataComponentKeys.CUSTOM_DATA, customData);
    }

    /** Removes {@code key}; every other key of the custom data component is preserved. */
    public static void remove(ItemStack item, String key) {
        if (item == null || key == null || key.isBlank()) {
            return;
        }
        Item wrapped = BukkitItemManager.instance().wrap(item);
        Tag existing = wrapped.getComponentAsSparrowTag(DataComponentKeys.CUSTOM_DATA);
        if (!(existing instanceof CompoundTag customData)) {
            return;
        }
        customData.remove(key);
        wrapped.setSparrowTagComponent(DataComponentKeys.CUSTOM_DATA, customData);
    }

    @Nullable
    private static CompoundTag packedCompound(ItemStack item, String key) {
        Item wrapped = BukkitItemManager.instance().wrap(item);
        Tag component = wrapped.getComponentAsSparrowTag(DataComponentKeys.CUSTOM_DATA);
        if (!(component instanceof CompoundTag customData)) {
            return null;
        }
        return customData.getCompound(key);
    }

    static PackedData fromTag(CompoundTag tag) {
        Tree tree = new Tree();
        for (Map.Entry<String, Tag> entry : tag.entrySet()) {
            Tag value = entry.getValue();
            String key = entry.getKey();
            if (value instanceof CompoundTag compound) {
                tree.values.put(key, fromTag(compound));
            } else if (value instanceof ByteTag) {
                // NBT has no boolean: the api writes booleans as bytes and no other value as a byte.
                tree.values.put(key, ((ByteTag) value).getAsByte() != 0);
            } else if (value instanceof IntTag) {
                tree.values.put(key, ((NumericTag) value).getAsInt());
            } else if (value instanceof LongTag) {
                tree.values.put(key, ((NumericTag) value).getAsLong());
            } else if (value instanceof NumericTag) {
                tree.values.put(key, ((NumericTag) value).getAsDouble());
            } else if (value instanceof StringTag) {
                tree.values.put(key, value.getAsString());
            } else if (value != null) {
                tree.values.put(key, value.getAsString());
            }
        }
        return tree;
    }

    static CompoundTag toTag(PackedData data) {
        CompoundTag tag = new CompoundTag();
        if (!(data instanceof Tree tree)) {
            // The api only hands out Tree instances; store a foreign implementation as text rather than failing.
            for (String key : data.keys()) {
                tag.putString(key, data.string(key, ""));
            }
            return tag;
        }
        for (Map.Entry<String, Object> entry : tree.values.entrySet()) {
            Object value = entry.getValue();
            String key = entry.getKey();
            if (value instanceof PackedData nested) {
                tag.put(key, toTag(nested));
            } else if (value instanceof ItemStack stack) {
                Tag itemTag = ItemUtils.saveBukkitItemAsTag(stack);
                if (itemTag != null) {
                    tag.put(key, itemTag);
                }
            } else if (value instanceof Boolean flag) {
                tag.putBoolean(key, flag);
            } else if (value instanceof Integer number) {
                tag.putInt(key, number);
            } else if (value instanceof Long number) {
                tag.putLong(key, number);
            } else if (value instanceof Double number) {
                tag.putDouble(key, number);
            } else if (value instanceof String text) {
                tag.putString(key, text);
            }
        }
        return tag;
    }

    /** The live tree handed to callers; kept out of the api jar. */
    private static final class Tree implements PackedData {

        private final Map<String, Object> values = new LinkedHashMap<>();

        @Override
        public boolean isEmpty() {
            return values.isEmpty();
        }

        @Override
        public Set<String> keys() {
            return Set.copyOf(values.keySet());
        }

        @Override
        public @Nullable PackedData compound(String key) {
            return values.get(key) instanceof PackedData tree ? tree : null;
        }

        @Override
        public String string(String key, String fallback) {
            return values.get(key) instanceof String text ? text : fallback;
        }

        @Override
        public int integer(String key, int fallback) {
            return values.get(key) instanceof Number number ? number.intValue() : fallback;
        }

        @Override
        public long longValue(String key, long fallback) {
            return values.get(key) instanceof Number number ? number.longValue() : fallback;
        }

        @Override
        public double decimal(String key, double fallback) {
            return values.get(key) instanceof Number number ? number.doubleValue() : fallback;
        }

        @Override
        public boolean bool(String key, boolean fallback) {
            return values.get(key) instanceof Boolean flag ? flag : fallback;
        }

        @Override
        public @Nullable ItemStack item(String key) {
            return values.get(key) instanceof ItemStack stack ? stack.clone() : null;
        }

        @Override
        public PackedData putCompound(String key, PackedData value) {
            if (key != null && value != null) {
                values.put(key, value);
            }
            return this;
        }

        @Override
        public PackedData putString(String key, String value) {
            if (key != null && value != null) {
                values.put(key, value);
            }
            return this;
        }

        @Override
        public PackedData putInt(String key, int value) {
            if (key != null) {
                values.put(key, value);
            }
            return this;
        }

        @Override
        public PackedData putLong(String key, long value) {
            if (key != null) {
                values.put(key, value);
            }
            return this;
        }

        @Override
        public PackedData putDouble(String key, double value) {
            if (key != null) {
                values.put(key, value);
            }
            return this;
        }

        @Override
        public PackedData putBool(String key, boolean value) {
            if (key != null) {
                values.put(key, value);
            }
            return this;
        }

        @Override
        public PackedData putItem(String key, ItemStack value) {
            if (key != null && value != null) {
                values.put(key, value.clone());
            }
            return this;
        }

        @Override
        public void remove(String key) {
            values.remove(key);
        }
    }
}
