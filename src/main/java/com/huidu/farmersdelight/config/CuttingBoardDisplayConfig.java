package com.huidu.farmersdelight.config;

import com.huidu.farmersdelight.util.ItemUtils;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CuttingBoardDisplayConfig {

    private static final float DEFAULT_ITEM_SPREAD = 0.15F;
    private static final Vector3f ZERO_OFFSET = new Vector3f(0.0F, 0.0F, 0.0F);

    private final Map<String, DisplayOverride> overrides = new HashMap<>();
    private Vector3f defaultOffset = new Vector3f(ZERO_OFFSET);
    private float itemSpread = DEFAULT_ITEM_SPREAD;

    public void loadFromConfig(ConfigurationSection section) {
        overrides.clear();
        defaultOffset = new Vector3f(ZERO_OFFSET);
        itemSpread = DEFAULT_ITEM_SPREAD;
        if (section == null) {
            return;
        }

        Vector3f configuredDefaultOffset = DisplayOverride.readVector(
                section,
                "default-display-position",
                "default-display-offset",
                "default-position",
                "default-offset"
        );
        if (configuredDefaultOffset != null) {
            defaultOffset = configuredDefaultOffset;
        }
        itemSpread = Math.max(0.0F, (float) section.getDouble(
                "display-item-spread",
                section.getDouble("item-spread", DEFAULT_ITEM_SPREAD)
        ));

        ConfigurationSection displaySection = section.getConfigurationSection("display-overrides");
        if (displaySection == null) {
            return;
        }

        for (String itemId : displaySection.getKeys(false)) {
            if (!ItemUtils.isValidItemId(itemId)) {
                continue;
            }
            ConfigurationSection itemSection = displaySection.getConfigurationSection(itemId);
            if (itemSection == null) {
                continue;
            }
            overrides.put(normalize(itemId), DisplayOverride.fromConfig(itemSection));
        }
    }

    public DisplayOverride getOverride(ItemStack storedItem) {
        String itemId = getItemId(storedItem);
        if (itemId == null) {
            return DisplayOverride.empty();
        }
        return overrides.getOrDefault(normalize(itemId), DisplayOverride.empty());
    }

    public ItemStack resolveDisplayItem(ItemStack storedItem) {
        if (storedItem == null || storedItem.getType().isAir()) {
            return null;
        }

        DisplayOverride override = getOverride(storedItem);
        if (override.displayItemId() != null) {
            ItemStack displayItem = ItemUtils.createItem(override.displayItemId());
            if (displayItem != null && !displayItem.getType().isAir()) {
                displayItem.setAmount(1);
                return displayItem;
            }
        }

        ItemStack fallback = storedItem.clone();
        fallback.setAmount(1);
        return fallback;
    }

    public Vector3f getDefaultOffset() {
        return new Vector3f(defaultOffset);
    }

    public float getItemSpread() {
        return itemSpread;
    }

    private String normalize(String itemId) {
        return itemId == null ? "" : itemId.trim().toLowerCase(Locale.ROOT);
    }

    private String getItemId(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        String customId = ItemUtils.getCustomItemId(item);
        if (customId != null) {
            return customId;
        }
        NamespacedKey key = Registry.MATERIAL.getKey(item.getType());
        return key == null ? null : key.toString();
    }

    public record DisplayOverride(
            @Nullable String displayItemId,
            DisplayStyle style,
            @Nullable Vector3f offset,
            @Nullable Vector3f translation,
            @Nullable Vector3f rotationDegrees,
            @Nullable Vector3f scale
    ) {
        private static final DisplayOverride EMPTY = new DisplayOverride(null, DisplayStyle.AUTO, null, null, null, null);

        public static DisplayOverride empty() {
            return EMPTY;
        }

        private static DisplayOverride fromConfig(ConfigurationSection section) {
            String displayItemId = section.getString("display-item");
            if (!ItemUtils.isValidItemId(displayItemId)) {
                displayItemId = null;
            }
            return new DisplayOverride(
                    displayItemId,
                    DisplayStyle.fromConfig(section.getString("style")),
                    readVector(section, "position", "offset"),
                    readVector(section, "translation"),
                    readVector(section, "rotation"),
                    readVector(section, "scale")
            );
        }

        @Nullable
        static Vector3f readVector(ConfigurationSection section, String... keys) {
            for (String key : keys) {
                if (!section.contains(key)) {
                    continue;
                }
                Object value = section.get(key);
                Vector3f vector = parseVector(value);
                if (vector != null) {
                    return vector;
                }
            }
            return null;
        }

        @Nullable
        private static Vector3f parseVector(Object value) {
            try {
                if (value instanceof Number number) {
                    float v = number.floatValue();
                    return new Vector3f(v, v, v);
                }
                if (value instanceof List<?> list) {
                    if (list.size() == 1) {
                        float v = Float.parseFloat(list.getFirst().toString());
                        return new Vector3f(v, v, v);
                    }
                    if (list.size() >= 3) {
                        return new Vector3f(
                                Float.parseFloat(list.get(0).toString()),
                                Float.parseFloat(list.get(1).toString()),
                                Float.parseFloat(list.get(2).toString())
                        );
                    }
                }
                if (value instanceof String string) {
                    String[] split = string.replace("_", "").split(",");
                    if (split.length == 1) {
                        float v = Float.parseFloat(split[0].trim());
                        return new Vector3f(v, v, v);
                    }
                    if (split.length >= 3) {
                        return new Vector3f(
                                Float.parseFloat(split[0].trim()),
                                Float.parseFloat(split[1].trim()),
                                Float.parseFloat(split[2].trim())
                        );
                    }
                }
                if (value instanceof ConfigurationSection vectorSection) {
                    return new Vector3f(
                            (float) vectorSection.getDouble("x", 0.0D),
                            (float) vectorSection.getDouble("y", 0.0D),
                            (float) vectorSection.getDouble("z", 0.0D)
                    );
                }
            } catch (Exception ignored) {
            }
            return null;
        }
    }

    public enum DisplayStyle {
        AUTO,
        ITEM,
        BLOCK;

        private static DisplayStyle fromConfig(@Nullable String value) {
            if (value == null || value.isBlank()) {
                return AUTO;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
                case "item", "flat", "generated" -> ITEM;
                case "block", "block_style", "blockstyle", "cube" -> BLOCK;
                default -> AUTO;
            };
        }
    }
}
