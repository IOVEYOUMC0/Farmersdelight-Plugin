package com.huidu.farmersdelight.util;

import net.momirealms.craftengine.core.block.property.Property;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class BehaviorArgParser {

    private BehaviorArgParser() {
    }

    /**
     * The value written for a key, trying the given spelling first and the alternate hyphen or
     * underscore spelling second. CraftEngine's own behaviors accept both grow_speed and grow-speed
     * for every key, so a config written in either convention resolves the same way here. The given
     * spelling always wins when its value is present, so existing hyphenated configs and the deployed
     * server keep their meaning even if the underscore form is also written.
     */
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

    /** The raw value for a key, trying both the given and the alternate spelling, for callers that need the
     *  unconverted object rather than a typed accessor. Returns null when neither spelling is present. */
    public static Object getRaw(Map<String, Object> arguments, String key) {
        return resolve(arguments, key);
    }

    /**
     * The same key with every '-' turned into '_' and every '_' into '-', or null when the key has
     * no such separator and therefore no alternate spelling to try.
     */
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

    /** Lenient: any non-null value is coerced via String.valueOf; only null yields the fallback.
     *  Use when the YAML value can legally be a non-String type (e.g. an integer that should print as text). */
    public static String getString(Map<String, Object> arguments, String key, String defaultValue) {
        Object value = resolve(arguments, key);
        if (value != null) {
            return String.valueOf(value);
        }
        return defaultValue;
    }

    /** Strict: only an actual non-empty String value passes through; anything else (null, wrong type,
     *  empty string) returns the fallback. Use to reject malformed configs early instead of silently coercing. */
    public static String getStringStrict(Map<String, Object> arguments, String key, String fallback) {
        Object value = resolve(arguments, key);
        return value instanceof String s && !s.isEmpty() ? s : fallback;
    }

    /** Lenient: a non-null Boolean passes through, a String is parsed via Boolean#parseBoolean,
     *  everything else falls back. */
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

    /** Strict: only an actual Boolean value passes through; quoted strings ("true"/"false") and any
     *  other type return the fallback. Use to reject malformed configs early. */
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

    /**
     * True when the key is written in the config at all, regardless of whether its value parses.
     * Distinct from hasArgument, which additionally rejects blank text: presence is what decides
     * which of two competing spellings of the same setting the author actually wrote, so a value
     * that later fails to parse still counts as written.
     */
    public static boolean isPresent(Map<String, Object> arguments, String key) {
        return resolve(arguments, key) != null;
    }

    /**
     * The nested map written under the key, or null when the key is absent or carries a scalar
     * instead of sub-keys. A caller that needs to tell those two cases apart (absent is normal,
     * scalar is an authoring mistake worth reporting) checks isPresent first.
     *
     * YAML sub-keys arrive as a plain Map in the raw argument map, so a nested section needs no
     * engine-side section type to read.
     */
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

