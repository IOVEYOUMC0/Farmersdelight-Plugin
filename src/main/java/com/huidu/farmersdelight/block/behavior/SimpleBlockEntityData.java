package com.huidu.farmersdelight.block.behavior;

import net.momirealms.craftengine.bukkit.util.ItemStackUtils;
import com.huidu.farmersdelight.util.ItemUtils;
import net.momirealms.craftengine.core.plugin.config.Config;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.libraries.nbt.NumericTag;
import net.momirealms.craftengine.libraries.nbt.Tag;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class SimpleBlockEntityData {
    private SimpleBlockEntityData() {
    }

    static CompoundTag save(Map<String, Object> data) {
        CompoundTag tag = new CompoundTag();
        if (data == null || data.isEmpty()) {
            return tag;
        }
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key == null || key.isBlank() || value == null) {
                continue;
            }
            if (value instanceof ItemStack itemStack) {
                if (itemStack.getType().isAir()) {
                    continue;
                }
                Tag itemTag = ItemUtils.saveBukkitItemAsTag(itemStack);
                if (itemTag != null) {
                    tag.put(key, itemTag);
                }
            } else if (value instanceof Integer integer) {
                tag.putInt(key, integer);
            } else if (value instanceof Number number) {
                tag.putInt(key, number.intValue());
            } else if (value instanceof Boolean bool) {
                tag.putBoolean(key, bool);
            } else {
                tag.putString(key, String.valueOf(value));
            }
        }
        return tag;
    }

    static Map<String, Object> load(CompoundTag tag, String... itemKeys) {
        Map<String, Object> data = new HashMap<>();
        if (tag == null) {
            return data;
        }
        Set<String> itemKeySet = new HashSet<>();
        if (itemKeys != null) {
            for (String key : itemKeys) {
                if (key != null && !key.isBlank()) {
                    itemKeySet.add(key);
                }
            }
        }
        for (String key : tag.keySet()) {
            Tag value = tag.get(key);
            if (value == null) {
                continue;
            }
            if (itemKeySet.contains(key)) {
                ItemStack item;
                try {
                    item = ItemStackUtils.parseBukkitItem(value, Config.itemDataFixerUpperFallbackVersion());
                } catch (RuntimeException e) {
                    // Corrupt/version-skewed item: skip this key rather than aborting the whole load.
                    com.huidu.farmersdelight.FarmersDelightPlugin.getInstance().getLogger()
                            .warning("Skipping unreadable block-entity item '" + key + "': " + e.getMessage());
                    continue;
                }
                if (item != null && !item.getType().isAir()) {
                    data.put(key, item);
                }
                continue;
            }
            String text = value.getAsString();
            if (text == null) {
                continue;
            }
            // Preserve tag types: only NumericTag values become Integer; numeric-looking strings remain strings.
            if (value instanceof NumericTag) {
                Integer integer = parseInteger(text);
                data.put(key, integer != null ? integer : text);
            } else {
                data.put(key, text);
            }
        }
        return data;
    }

    private static Integer parseInteger(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
