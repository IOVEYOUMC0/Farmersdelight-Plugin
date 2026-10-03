package com.huidu.farmersdelight.api.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads one block/item behavior argument from the raw argument map CraftEngine hands to a behavior factory,
 * accepting both the nested style and the legacy flat key. A dotted path is resolved through nested maps first
 * (burn.enabled from burn: {enabled: ...}) and then falls back to the flat key built from the same
 * segments (burn-enabled, also burn_enabled), so packs written before the nested style keep
 * working. Callers decide their own default and validation; this class only answers "which raw value applies".
 *
 *
 * It exists in the api package because addons write their own behavior factories and compile against the api
 * jar only. FarmersDelight's own behaviors read through
 * com.huidu.farmersdelight.util.BehaviorArgParser, which adds typed parsing and loud validation on top of
 * the same lookup.
 */
public final class BehaviorArguments {

    private BehaviorArguments() {
    }

    /**
     * The raw value for path, or null when neither the nested path nor its flat spelling is present.
     * The nested form wins when both are set.
     */
    public static Object raw(Map<String, Object> arguments, String path) {
        if (arguments == null || path == null) {
            return null;
        }
        if (path.indexOf('.') < 0) {
            return flat(arguments, path);
        }
        Object nested = nested(arguments, path);
        return nested != null ? nested : flat(arguments, path.replace('.', '-'));
    }

    /**
     * The raw value for path, falling back to legacyKey when neither the nested path nor the flat
     * spelling derived from it is present. Use this when the old flat key does not follow the
     * <section>-<key> spelling, for example
     * rawWithLegacy(arguments, "bone-meal.age-bonus", "bone_meal_age_bonus").
     */
    public static Object rawWithLegacy(Map<String, Object> arguments, String path, String legacyKey) {
        Object value = raw(arguments, path);
        return value != null ? value : flat(arguments, legacyKey);
    }

    /** True when the path (nested) or its flat spelling is present. */
    public static boolean present(Map<String, Object> arguments, String path) {
        if (arguments == null || path == null) {
            return false;
        }
        if (path.indexOf('.') < 0) {
            return containsFlat(arguments, path);
        }
        if (nested(arguments, path) != null) {
            return true;
        }
        return containsFlat(arguments, path.replace('.', '-'));
    }

    /** The nested section at path (for example burn), or null when there is no such map. */
    public static Map<String, Object> section(Map<String, Object> arguments, String path) {
        Object value = path != null && path.indexOf('.') >= 0 ? nested(arguments, path) : flat(arguments, path);
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof String key) {
                result.put(key, entry.getValue());
            }
        }
        return result;
    }

    public static Boolean bool(Map<String, Object> arguments, String path, Boolean fallback) {
        Object value = raw(arguments, path);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            String normalized = text.trim();
            if (normalized.equalsIgnoreCase("true") || normalized.equalsIgnoreCase("yes") || normalized.equalsIgnoreCase("on")) {
                return Boolean.TRUE;
            }
            if (normalized.equalsIgnoreCase("false") || normalized.equalsIgnoreCase("no") || normalized.equalsIgnoreCase("off")) {
                return Boolean.FALSE;
            }
        }
        return fallback;
    }

    public static Float decimal(Map<String, Object> arguments, String path, Float fallback) {
        Object value = raw(arguments, path);
        if (value instanceof Number number) {
            return number.floatValue();
        }
        if (value instanceof String text) {
            try {
                return Float.parseFloat(text.trim().replace("_", ""));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public static Integer integer(Map<String, Object> arguments, String path, Integer fallback) {
        Object value = raw(arguments, path);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim().replace("_", ""));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public static String text(Map<String, Object> arguments, String path, String fallback) {
        Object value = raw(arguments, path);
        return value == null ? fallback : String.valueOf(value);
    }

    private static Object nested(Map<String, Object> arguments, String path) {
        Map<?, ?> current = arguments;
        int from = 0;
        while (true) {
            int dot = path.indexOf('.', from);
            String segment = dot < 0 ? path.substring(from) : path.substring(from, dot);
            Object value = current.get(segment);
            if (value == null) {
                return null;
            }
            if (dot < 0) {
                return value;
            }
            if (!(value instanceof Map<?, ?> map)) {
                return null;
            }
            current = map;
            from = dot + 1;
        }
    }

    private static Object flat(Map<String, Object> arguments, String key) {
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

    private static boolean containsFlat(Map<String, Object> arguments, String key) {
        if (arguments.containsKey(key)) {
            return true;
        }
        String alternate = alternateSpelling(key);
        return alternate != null && arguments.containsKey(alternate);
    }

    // The same key in the other spelling: "grow-speed" <-> "grow_speed", "light_requirement" <-> "light-requirement".
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
}
