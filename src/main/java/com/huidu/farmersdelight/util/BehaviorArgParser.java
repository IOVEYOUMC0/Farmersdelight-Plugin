package com.huidu.farmersdelight.util;

import net.momirealms.craftengine.core.block.property.Property;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class BehaviorArgParser {

    private BehaviorArgParser() {
    }

    private static Object resolve(Map<String, Object> arguments, String key) {
        if (arguments == null || key == null) {
            return null;
        }
        Object value = arguments.get(key);
        if (value != null) {
            return value;
        }
        String alternate = alternateSpelling(key);
        return alternate != null ? arguments.get(alternate) : null;
    }

    public static Object getRaw(Map<String, Object> arguments, String key) {
        return resolve(arguments, key);
    }

    private static String alternateSpelling(String key) {
        boolean changed = false;
        char[] chars = key.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c == '-') {
                chars[i] = '_';
                changed = true;
            } else if (c == '_') {
                chars[i] = '-';
                changed = true;
            }
        }
        return changed ? new String(chars) : null;
    }

    public static String getString(Map<String, Object> arguments, String key, String defaultValue) {
        Object value = resolve(arguments, key);
        if (value != null) {
            return String.valueOf(value);
        }
        return defaultValue;
    }

    public static String getStringStrict(Map<String, Object> arguments, String key, String fallback) {
        Object value = resolve(arguments, key);
        return value instanceof String s && !s.isEmpty() ? s : fallback;
    }

    public static boolean getBoolean(Map<String, Object> arguments, String key, boolean defaultValue) {
        Object value = resolve(arguments, key);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof String stringValue) {
            return Boolean.parseBoolean(stringValue);
        }
        return defaultValue;
    }

    public static boolean getBooleanStrict(Map<String, Object> arguments, String key, boolean fallback) {
        Object value = resolve(arguments, key);
        return value instanceof Boolean b ? b : fallback;
    }

    public static int getInt(Map<String, Object> arguments, String key, int defaultValue) {
        Object value = resolve(arguments, key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String stringValue) {
            try {
                return Integer.parseInt(stringValue);
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    public static float getFloat(Map<String, Object> arguments, String key, float defaultValue) {
        Object value = resolve(arguments, key);
        if (value instanceof Number number) {
            return number.floatValue();
        }
        if (value instanceof String stringValue) {
            try {
                return Float.parseFloat(stringValue);
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    public static double getDouble(Map<String, Object> arguments, String key, double defaultValue) {
        Object value = resolve(arguments, key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String stringValue) {
            try {
                return Double.parseDouble(stringValue);
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    public static boolean isPresent(Map<String, Object> arguments, String key) {
        return resolve(arguments, key) != null;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getSection(Map<String, Object> arguments, String key) {
        Object value = resolve(arguments, key);
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        for (Object mapKey : map.keySet()) {
            if (!(mapKey instanceof String)) {
                return null;
            }
        }
        return (Map<String, Object>) map;
    }

    public static boolean hasArgument(Map<String, Object> arguments, String key) {
        Object value = resolve(arguments, key);
        return value != null && !String.valueOf(value).trim().isEmpty();
    }

    public static int inferMaxIntegerValue(Property<Integer> property, int fallback) {
        if (property == null) {
            return fallback;
        }
        try {
            List<Integer> values = property.possibleValues();
            if (values == null || values.isEmpty()) {
                return fallback;
            }
            return Collections.max(values);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public static String getArgumentString(Map<String, Object> arguments, String key, String defaultValue) {
        Object value = resolve(arguments, key);
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return defaultValue;
        }
        return text;
    }
}

