package com.huidu.farmersdelight.util;

import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.plugin.config.ConfigConstants;
import net.momirealms.craftengine.core.plugin.config.KnownResourceException;

import java.util.Collections;
import java.util.LinkedHashMap;
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

    private static boolean present(Map<String, Object> arguments, String key) {
        if (arguments == null || key == null) {
            return false;
        }
        if (arguments.containsKey(key)) {
            return true;
        }
        String alternate = alternateSpelling(key);
        return alternate != null && arguments.containsKey(alternate);
    }

    private static String valueText(Object value) {
        return String.valueOf(value);
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
        if (value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Character) {
            return String.valueOf(value);
        }
        if (present(arguments, key)) {
            throw new KnownResourceException(ConfigConstants.PARSE_NONEMPTY_STRING_FAILED, key, valueText(value));
        }
        return defaultValue;
    }

    public static String getStringStrict(Map<String, Object> arguments, String key, String fallback) {
        Object value = resolve(arguments, key);
        if (!present(arguments, key)) {
            return fallback;
        }
        if (value instanceof String s && !s.isBlank()) {
            return s;
        }
        throw new KnownResourceException(ConfigConstants.PARSE_NONEMPTY_STRING_FAILED, key, valueText(value));
    }

    public static boolean getBoolean(Map<String, Object> arguments, String key, boolean defaultValue) {
        Object value = resolve(arguments, key);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof String stringValue) {
            String normalized = stringValue.trim();
            if (normalized.equalsIgnoreCase("true") || normalized.equalsIgnoreCase("yes") || normalized.equalsIgnoreCase("on")) {
                return true;
            }
            if (normalized.equalsIgnoreCase("false") || normalized.equalsIgnoreCase("no") || normalized.equalsIgnoreCase("off")) {
                return false;
            }
        }
        if (value instanceof Number number) {
            if (number.doubleValue() == 0.0D) {
                return false;
            }
            if (number.doubleValue() > 0.0D) {
                return true;
            }
        }
        if (present(arguments, key)) {
            throw new KnownResourceException(ConfigConstants.PARSE_BOOLEAN_FAILED, key, valueText(value));
        }
        return defaultValue;
    }

    public static boolean getBooleanStrict(Map<String, Object> arguments, String key, boolean fallback) {
        return getBoolean(arguments, key, fallback);
    }

    public static int getInt(Map<String, Object> arguments, String key, int defaultValue) {
        Object value = resolve(arguments, key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String stringValue) {
            try {
                return Integer.parseInt(stringValue.trim().replace("_", ""));
            } catch (NumberFormatException ignored) {
            }
        }
        if (present(arguments, key)) {
            throw new KnownResourceException(ConfigConstants.PARSE_INT_FAILED, key, valueText(value));
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
                return Float.parseFloat(stringValue.trim().replace("_", ""));
            } catch (NumberFormatException ignored) {
            }
        }
        if (present(arguments, key)) {
            throw new KnownResourceException(ConfigConstants.PARSE_FLOAT_FAILED, key, valueText(value));
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
                return Double.parseDouble(stringValue.trim().replace("_", ""));
            } catch (NumberFormatException ignored) {
            }
        }
        if (present(arguments, key)) {
            throw new KnownResourceException(ConfigConstants.PARSE_DOUBLE_FAILED, key, valueText(value));
        }
        return defaultValue;
    }

    public static List<String> getStringList(Map<String, Object> arguments, String key) {
        Object value = resolve(arguments, key);
        if (!present(arguments, key)) {
            return List.of();
        }
        if (!(value instanceof Iterable<?> iterable)) {
            throw new KnownResourceException(ConfigConstants.PARSE_LIST_FAILED, key, valueText(value));
        }
        List<String> result = new java.util.ArrayList<>();
        for (Object entry : iterable) {
            if (entry == null || !(entry instanceof String || entry instanceof Number
                    || entry instanceof Boolean || entry instanceof Character)) {
                throw new KnownResourceException(ConfigConstants.PARSE_LIST_FAILED, key, valueText(value));
            }
            result.add(String.valueOf(entry));
        }
        return List.copyOf(result);
    }

    public static boolean isPresent(Map<String, Object> arguments, String key) {
        return resolve(arguments, key) != null;
    }

    public static Map<String, Object> getSection(Map<String, Object> arguments, String key) {
        Object value = resolve(arguments, key);
        if (!present(arguments, key)) {
            return null;
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new KnownResourceException(ConfigConstants.PARSE_SECTION_FAILED, key, valueText(value));
        }
        Map<String, Object> section = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String mapKey)) {
                throw new KnownResourceException(ConfigConstants.PARSE_SECTION_FAILED, key, valueText(value));
            }
            section.put(mapKey, entry.getValue());
        }
        return section;
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
        return getStringStrict(arguments, key, defaultValue).trim();
    }
}

