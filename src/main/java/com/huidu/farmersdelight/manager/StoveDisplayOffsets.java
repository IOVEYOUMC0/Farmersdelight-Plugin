package com.huidu.farmersdelight.manager;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Arrays;
import java.util.List;

final class StoveDisplayOffsets {

    private static final double[][] DEFAULT = {
            {0.3, 1.02, 0.2}, {0.0, 1.02, 0.2}, {-0.3, 1.02, 0.2},
            {0.3, 1.02, -0.2}, {0.0, 1.02, -0.2}, {-0.3, 1.02, -0.2}
    };

    private StoveDisplayOffsets() {
    }

    static double[][] defaults() {
        return copy();
    }

    static double[][] copy() {
        double[][] result = new double[StoveDisplayOffsets.DEFAULT.length][];
        for (int i = 0; i < StoveDisplayOffsets.DEFAULT.length; i++) {
            result[i] = Arrays.copyOf(StoveDisplayOffsets.DEFAULT[i], StoveDisplayOffsets.DEFAULT[i].length);
        }
        return result;
    }

    static double[][] load(FarmersDelightPlugin plugin, int slotCount) {
        double[][] loaded = copy();
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
