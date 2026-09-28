package com.huidu.farmersdelight.util;

import com.huidu.farmersdelight.api.config.BehaviorArguments;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.plugin.config.ConfigConstants;
import net.momirealms.craftengine.core.plugin.config.KnownResourceException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BehaviorArgParser {
    private static final String PARSE_LIST_FAILED = "resource.argument.parser.list";

    private BehaviorArgParser() {
    }

    /**
     * Resolves an argument that may be written either as a nested section path (burn.enabled from
     * burn: {enabled: ...}) or as the legacy flat key (burn-enabled, also burn_enabled),
     * so a pack written before the nested style keeps working unchanged. The lookup itself lives in the api
     * facade (BehaviorArguments) because addon behavior factories read through it too.
     */
    private static Object resolve(Map<String, Object> arguments, String key) {
        return BehaviorArguments.raw(arguments, key);
    }

    private static boolean present(Map<String, Object> arguments, String key) {
        return BehaviorArguments.present(arguments, key);
    }

    private static String valueText(Object value) {
        return String.valueOf(value);
    }

    public static Object getRaw(Map<String, Object> arguments, String key) {
        return resolve(arguments, key);
    }

    /**
     * The raw value for path, falling back to legacyKey for a grouped option whose old flat key
     * does not follow the path spelling; used by callers that must inspect the raw value themselves (a section that
     * must stay a section, or a scalar that has to be told apart from one).
     */
    public static Object getRaw(Map<String, Object> arguments, String path, String legacyKey) {
        return BehaviorArguments.rawWithLegacy(arguments, path, legacyKey);
    }

    /** The nested section at path (for example burn), or null when there is no such map. */
    public static Map<String, Object> getNestedSection(Map<String, Object> arguments, String path) {
        return BehaviorArguments.section(arguments, path);
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
            throw new KnownResourceException(PARSE_LIST_FAILED, key, valueText(value));
        }
        List<String> result = new ArrayList<>();
        for (Object entry : iterable) {
            if (entry == null || !(entry instanceof String || entry instanceof Number
                    || entry instanceof Boolean || entry instanceof Character)) {
                throw new KnownResourceException(PARSE_LIST_FAILED, key, valueText(value));
            }
            result.add(String.valueOf(entry));
        }
        return List.copyOf(result);
    }

    public static boolean isPresent(Map<String, Object> arguments, String key) {
        return resolve(arguments, key) != null;
    }

    /**
     * As getBoolean, but for a grouped option whose legacy flat key does not follow the path spelling
     * (brown-mushroom-colony for mushroom-colony.brown). The nested path is tried first, then its
     * own flat spelling, then legacyKey.
     */
    public static boolean getBoolean(Map<String, Object> arguments, String path, String legacyKey, boolean defaultValue) {
        return getBoolean(resolveWithLegacy(arguments, path, legacyKey), path, defaultValue);
    }

    public static String getStringStrict(Map<String, Object> arguments, String path, String legacyKey, String fallback) {
        return getStringStrict(resolveWithLegacy(arguments, path, legacyKey), path, fallback);
    }

    public static String getString(Map<String, Object> arguments, String path, String legacyKey, String defaultValue) {
        return getString(resolveWithLegacy(arguments, path, legacyKey), path, defaultValue);
    }

    public static boolean isPresent(Map<String, Object> arguments, String path, String legacyKey) {
        return present(arguments, path) || present(arguments, legacyKey);
    }

    // Returns the original map, or a copy that carries the legacy value under the path, so the typed readers keep
    // their parsing and their error node (the path).
    private static Map<String, Object> resolveWithLegacy(Map<String, Object> arguments, String path, String legacyKey) {
        if (arguments == null || path == null || present(arguments, path)) {
            return arguments;
        }
        Object legacy = BehaviorArguments.rawWithLegacy(arguments, path, legacyKey);
        if (legacy == null) {
            return arguments;
        }
        Map<String, Object> view = new LinkedHashMap<>(arguments);
        return withPath(view, path, legacy);
    }

    // The readers look a dotted path up through nested maps, so the copy has to place the legacy value the same
    // way; a copy carrying the path as one literal key ("bone-meal.is-target") is never found again by that
    // lookup, which would leave the option silently at its default. An already configured sibling in the same
    // section is merged in, so adding the legacy value does not hide it.
    private static Map<String, Object> withPath(Map<String, Object> view, String path, Object value) {
        int dot = path.lastIndexOf('.');
        if (dot < 0) {
            view.put(path, value);
            return view;
        }
        String parentPath = path.substring(0, dot);
        Map<String, Object> parent = new LinkedHashMap<>();
        Map<String, Object> configured = BehaviorArguments.section(view, parentPath);
        if (configured != null) {
            parent.putAll(configured);
        }
        parent.put(path.substring(dot + 1), value);
        return withPath(view, parentPath, parent);
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

