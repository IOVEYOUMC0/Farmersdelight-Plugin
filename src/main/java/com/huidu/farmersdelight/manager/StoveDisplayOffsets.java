package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Arrays;
import java.util.List;

/**
 * Parses the stove's per-slot item-display offsets from config into the [slot][x,y,z] matrix the visual
 * layer rotates and renders at. Extracted from StoveManager so the offset config-parsing (a pure,
 * config-time-only concern) lives apart from the manager's concurrent index + tick machinery. Every method is
 * static and returns a fresh array — it holds no state and never touches the manager's live maps or locks.
 */
final class StoveDisplayOffsets {

    /** Default centre-of-block offsets for the 6 stove cooking slots (3 front, 3 back), in block-space. */
    private static final double[][] DEFAULT = {
            {0.3, 1.02, 0.2}, {0.0, 1.02, 0.2}, {-0.3, 1.02, 0.2},
            {0.3, 1.02, -0.2}, {0.0, 1.02, -0.2}, {-0.3, 1.02, -0.2}
    };

    private StoveDisplayOffsets() {
    }

    /** A fresh copy of the built-in default offsets (used to seed the field before config is loaded). */
    static double[][] defaults() {
        return copy(DEFAULT);
    }

    /** Deep-copies an offset matrix so callers can mutate rows without aliasing a shared source. */
    static double[][] copy(double[][] source) {
        double[][] result = new double[source.length][];
        for (int i = 0; i < source.length; i++) {
            result[i] = Arrays.copyOf(source[i], source[i].length);
        }
        return result;
    }

    /** Loads slotCount slot offsets from stove.display / display-visuals.stove, falling
     *  back to the defaults for any slot the config omits or malforms. Supports both the slot-offsets
     *  list form and the slots.<n> section form. */
    static double[][] load(FarmersDelightPlugin plugin, int slotCount) {
        double[][] loaded = copy(DEFAULT);
        ConfigurationSection section = plugin.getFirstConfigSection("stove.display", "display-visuals.stove");
        if (section == null) {
            return loaded;
        }

        List<?> list = section.getList("slot-offsets");
        if (list != null && !list.isEmpty()) {
            for (int i = 0; i < Math.min(slotCount, list.size()); i++) {
                double[] parsed = parse(list.get(i));
                if (parsed != null) {
                    loaded[i] = parsed;
                }
            }
            return loaded;
        }

        ConfigurationSection slotsSection = section.getConfigurationSection("slots");
        if (slotsSection != null) {
            for (int i = 0; i < slotCount; i++) {
                double[] parsed = parse(slotsSection.get(String.valueOf(i)));
                if (parsed != null) {
                    loaded[i] = parsed;
                }
            }
        }
        return loaded;
    }

    /** Parses one offset vector from its list / comma-string / {x,y,z} section form; null if unrecognized. */
    private static double[] parse(Object value) {
        try {
            if (value instanceof List<?> list && list.size() >= 3) {
                return new double[]{
                        Double.parseDouble(list.get(0).toString()),
                        Double.parseDouble(list.get(1).toString()),
                        Double.parseDouble(list.get(2).toString())
                };
            }
            if (value instanceof String string) {
                String[] parts = string.replace("_", "").split(",");
                if (parts.length >= 3) {
                    return new double[]{
                            Double.parseDouble(parts[0].trim()),
                            Double.parseDouble(parts[1].trim()),
                            Double.parseDouble(parts[2].trim())
                    };
                }
            }
            if (value instanceof ConfigurationSection vectorSection) {
                return new double[]{
                        vectorSection.getDouble("x", 0.0D),
                        vectorSection.getDouble("y", 0.0D),
                        vectorSection.getDouble("z", 0.0D)
                };
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
