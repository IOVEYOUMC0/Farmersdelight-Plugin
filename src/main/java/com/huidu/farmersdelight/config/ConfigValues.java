package com.huidu.farmersdelight.config;

import java.util.Map;

/**
 * Reads scalars out of a raw option map.
 *
 * <p>com.huidu.farmersdelight.api.config.ConfigSectionReader covers the section-shaped input — YAML
 * files and CraftEngine's ConfigSection. The CE item-setting path and the nested reward entries hand
 * their fields over as plain maps instead, which that reader cannot take. Both used to carry their own copy of
 * the same coercion, with slightly different accepted inputs.
 *
 * <p>Unlike the section reader these do not throw on a present-but-uncoercible value: they return the default.
 * That is the behaviour both callers shipped with, and the value here belongs to a nested per-entry field whose
 * only recovery would be dropping the whole definition. Missing keys, unparseable strings and unusable types
 * all fall back the same way.
 */
final class ConfigValues {

    private ConfigValues() {
    }

    static int intValue(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    static double doubleValue(Object value, double defaultValue) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    static boolean booleanValue(Object value, boolean defaultValue) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text.trim());
        }
        // A non-boolean type keeps the configured default rather than reading as false.
        return defaultValue;
    }

    static Object firstPresent(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }
}
