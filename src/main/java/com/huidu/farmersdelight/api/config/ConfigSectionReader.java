package com.huidu.farmersdelight.api.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.List;

/**
 * A thin typed read facade over Bukkit ConfigurationSection that borrows SparrowYAML's idea of
 * explicit field presence: required fields fail loudly on absence, optional fields carry a default, and
 * a present-but-uncoercible value is reported as InvalidConfigKeyException rather than being
 * silently returned as null. The intent is a single, consistent read entry point across recipe, gui,
 * loot and lang configs so "missing" and "invalid" stop being conflated.
 */
public final class ConfigSectionReader {

    private ConfigSectionReader() {
    }

    /** Returns the string value or null when the key is missing/null. Fails only on non-scalar values. */
    public static String optionalString(ConfigurationSection section, String path) {
        Object value = valueOf(section, path);
        if (value == null) {
            return null;
        }
        if (value instanceof String string) {
            return string;
        }
        if (isScalar(value)) {
            return String.valueOf(value);
        }
        throw invalid(section, path, value, "a string");
    }

    /** Returns the string value, or defaultValue when the key is missing/null. */
    public static String optionalString(ConfigurationSection section, String path, String defaultValue,
                                        String... aliases) {
        Object value = valueAtFirstPresent(section, path, aliases);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof String string) {
            return string;
        }
        if (isScalar(value)) {
            return String.valueOf(value);
        }
        throw invalid(section, path, value, "a string");
    }

    /** Like optionalString, but throws MissingConfigKeyException on absence. */
    public static String requireString(ConfigurationSection section, String path) {
        String value = optionalString(section, path);
        if (value == null) {
            throw new MissingConfigKeyException(section, path);
        }
        return value;
    }

    /** Returns the int value or defaultValue when the key is missing/null. Throws on a bad value. */
    public static int optionalInt(ConfigurationSection section, String path, int defaultValue,
                                  String... aliases) {
        Object value = valueAtFirstPresent(section, path, aliases);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string) {
            try {
                return Integer.parseInt(string.trim());
            } catch (NumberFormatException e) {
                throw invalid(section, path, value, "an int", e);
            }
        }
        throw invalid(section, path, value, "an int");
    }

    /** Like optionalInt, but returns null (instead of a default) when no key is present. */
    public static Integer optionalIntOrNull(ConfigurationSection section, String path, String... aliases) {
        Object value = valueAtFirstPresent(section, path, aliases);
        if (value == null) {
            return null;
        }
        return (int) coercedInt(section, path, value);
    }

    private static int coercedInt(ConfigurationSection section, String path, Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string) {
            try {
                return Integer.parseInt(string.trim());
            } catch (NumberFormatException e) {
                throw invalid(section, path, value, "an int", e);
            }
        }
        throw invalid(section, path, value, "an int");
    }

    /** Like optionalInt(ConfigurationSection, String, int), but throws on absence. */
    public static int requireInt(ConfigurationSection section, String path) {
        if (valueOf(section, path) == null) {
            throw new MissingConfigKeyException(section, path);
        }
        return optionalInt(section, path, 0);
    }

    /** Like optionalInt, but for long values. Throws on a bad value. */
    public static long optionalLong(ConfigurationSection section, String path, long defaultValue,
                                    String... aliases) {
        Object value = valueAtFirstPresent(section, path, aliases);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String string) {
            try {
                return Long.parseLong(string.trim());
            } catch (NumberFormatException e) {
                throw invalid(section, path, value, "a long", e);
            }
        }
        throw invalid(section, path, value, "a long");
    }

    /** Returns the double value or defaultValue when the key is missing/null. Throws on a bad value. */
    public static double optionalDouble(ConfigurationSection section, String path, double defaultValue,
                                        String... aliases) {
        Object value = valueAtFirstPresent(section, path, aliases);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String string) {
            try {
                return Double.parseDouble(string.trim());
            } catch (NumberFormatException e) {
                throw invalid(section, path, value, "a double", e);
            }
        }
        throw invalid(section, path, value, "a double");
    }

    /** Returns the boolean value or defaultValue when the key is missing/null. Throws on a bad value. */
    public static boolean optionalBoolean(ConfigurationSection section, String path, boolean defaultValue,
                                          String... aliases) {
        Object value = valueAtFirstPresent(section, path, aliases);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String string) {
            String trimmed = string.trim();
            if (trimmed.equalsIgnoreCase("true")) {
                return true;
            }
            if (trimmed.equalsIgnoreCase("false")) {
                return false;
            }
        }
        throw invalid(section, path, value, "a boolean");
    }

    /** Returns a shallow-copied string list, or an empty list when the key is missing/null. */
    public static List<String> optionalStringList(ConfigurationSection section, String path,
                                                  String... aliases) {
        Object value = valueAtFirstPresent(section, path, aliases);
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            List<String> strings = new java.util.ArrayList<>(list.size());
            for (Object element : list) {
                if (element instanceof String || isScalar(element)) {
                    strings.add(String.valueOf(element));
                } else {
                    throw invalid(section, path, element, "a list of strings");
                }
            }
            return List.copyOf(strings);
        }
        throw invalid(section, path, value, "a list of strings");
    }

    /** Returns a list of map values, or an empty list when the key is missing/null. Throws on a bad value. */
    public static List<java.util.Map<?, ?>> optionalMapList(ConfigurationSection section, String path) {
        Object value = valueOf(section, path);
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            List<java.util.Map<?, ?>> maps = new java.util.ArrayList<>(list.size());
            for (Object e : list) {
                if (e instanceof java.util.Map<?, ?> map) {
                    maps.add(map);
                } else {
                    throw invalid(section, path, e, "a list of maps");
                }
            }
            return maps;
        }
        throw invalid(section, path, value, "a list of maps");
    }

    private static Object valueOf(ConfigurationSection section, String path) {
        return section == null ? null : section.get(path);
    }

    private static Object valueAtFirstPresent(ConfigurationSection section, String path, String... aliases) {
        Object value = valueOf(section, path);
        if (value != null) {
            return value;
        }
        for (String alias : aliases) {
            Object aliasValue = valueOf(section, alias);
            if (aliasValue != null) {
                return aliasValue;
            }
        }
        return null;
    }

    private static boolean isScalar(Object value) {
        return value instanceof Number || value instanceof Boolean || value instanceof Character;
    }

    private static InvalidConfigKeyException invalid(ConfigurationSection section, String path,
                                                     Object value, String expected) {
        return invalid(section, path, value, expected, null);
    }

    private static InvalidConfigKeyException invalid(ConfigurationSection section, String path,
                                                     Object value, String expected, Throwable cause) {
        return new InvalidConfigKeyException(section, path, value, expected, cause);
    }
}
