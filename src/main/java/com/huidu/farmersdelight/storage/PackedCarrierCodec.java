package com.huidu.farmersdelight.storage;

import com.huidu.farmersdelight.util.CustomBlockUtils;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.libraries.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Reads and writes the packed custom-data carrier: one nested compound per packed key, merged into the
 * component instead of replacing it so CraftEngine identity and other plugins' keys survive.
 */
public final class PackedCarrierCodec {

    private PackedCarrierCodec() {
    }

    /** The nested compound stored under {@code key}, or null when the component is absent or unreadable. */
    @Nullable
    public static CompoundTag read(Item item, Key component, String key) {
        if (item == null || component == null || key == null || key.isBlank()) {
            return null;
        }
        return CustomBlockUtils.getNestedComponentCompound(item, component, key);
    }

    /**
     * Stores {@code data} under {@code key}, preserving every other key of the component.
     * A null {@code data} removes the key, matching "no packed state".
     */
    public static void write(Item item, Key component, String key, @Nullable CompoundTag data) {
        if (item == null || component == null || key == null || key.isBlank()) {
            return;
        }
        CompoundTag customData = CustomBlockUtils.getComponentCompound(item, component);
        if (data == null) {
            if (customData == null) {
                return;
            }
            customData.remove(key);
        } else {
            if (customData == null) {
                customData = new CompoundTag();
            }
            customData.put(key, merge(customData.getCompound(key), data));
        }
        item.setSparrowTagComponent(component, customData);
    }

    /** Removes the nested compound under {@code key}; the rest of the component stays untouched. */
    public static void remove(Item item, Key component, String key) {
        write(item, component, key, null);
    }

    /**
     * Overlays {@code overlay} on {@code existing} without mutating either input. Nested compounds merge
     * recursively so a caller that only wrote a few fields cannot drop the others that were already packed.
     */
    public static CompoundTag merge(@Nullable CompoundTag existing, CompoundTag overlay) {
        CompoundTag merged = new CompoundTag();
        if (existing != null) {
            for (Map.Entry<String, Tag> entry : existing.entrySet()) {
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        if (overlay != null) {
            for (Map.Entry<String, Tag> entry : overlay.entrySet()) {
                Tag previous = merged.get(entry.getKey());
                if (previous instanceof CompoundTag previousCompound
                        && entry.getValue() instanceof CompoundTag overlayCompound) {
                    merged.put(entry.getKey(), merge(previousCompound, overlayCompound));
                } else {
                    merged.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return merged;
    }
}
