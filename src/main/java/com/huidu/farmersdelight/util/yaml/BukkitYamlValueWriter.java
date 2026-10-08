package com.huidu.farmersdelight.util.yaml;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders one entry of a recipe file, and checks the text an edit produced before it is written.
 *
 *
 * The value is serialised as a single entry of a scratch document by the same parser the loader reads recipe
 * files with, so a written recipe has exactly the shape a hand-written one has and no second YAML writer has to
 * be kept in step with it. The entry is reached through raw map keys rather than dotted paths, so a recipe id
 * containing a dot stays one key instead of becoming a nested section.
 */
public final class BukkitYamlValueWriter implements YamlFileTransaction.ValueWriter {

    /** The shared writer; it holds no state, so one instance serves every edit. */
    public static final BukkitYamlValueWriter INSTANCE = new BukkitYamlValueWriter();

    private BukkitYamlValueWriter() {
    }

    @Override
    public String block(String key, Object value, int depth) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put(key, value);
        YamlConfiguration scratch = new YamlConfiguration();
        scratch.set("scratch", entry);
        String text = scratch.saveToString();
        int shift = depth - 2;
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (line.isEmpty() || "scratch:".equals(line)) {
                continue;
            }
            String body = shift > 0 ? " ".repeat(shift) + line
                    : shift < 0 ? line.substring(Math.min(-shift, leadingSpaces(line))) : line;
            out.append(body).append('\n');
        }
        return out.toString();
    }

    /**
     * Checks that the produced text still parses and carries exactly what the edits asked for: the new value for
     * a write, nothing for a removal. A mismatch refuses the write, which is what keeps a mis-located edit from
     * ever reaching the file.
     */
    public static YamlFileTransaction.Check check(List<YamlFileTransaction.Edit> edits) {
        return text -> {
            YamlConfiguration parsed = new YamlConfiguration();
            try {
                parsed.loadFromString(text);
            } catch (InvalidConfigurationException broken) {
                return "the edited file would not parse: " + broken.getMessage();
            }
            for (YamlFileTransaction.Edit edit : edits) {
                if (edit instanceof YamlFileTransaction.SetValue set) {
                    Object found = read(parsed, set.path());
                    if (!sameValue(found, set.value())) {
                        return "the edited file does not carry the new value at " + String.join(".", set.path());
                    }
                } else if (edit instanceof YamlFileTransaction.RemoveValue remove
                        && read(parsed, remove.path()) != null) {
                    return "the edited file still carries " + String.join(".", remove.path());
                }
            }
            return null;
        };
    }

    private static Object read(ConfigurationSection section, List<String> path) {
        Object current = section;
        for (String segment : path) {
            if (current instanceof ConfigurationSection nested) {
                current = nested.getValues(false).get(segment);
            } else if (current instanceof Map<?, ?> map) {
                current = map.get(segment);
            } else {
                return null;
            }
        }
        return current;
    }

    private static boolean sameValue(Object found, Object expected) {
        Object left = found instanceof ConfigurationSection section ? section.getValues(false) : found;
        Object right = expected instanceof ConfigurationSection section ? section.getValues(false) : expected;
        if (left instanceof Map<?, ?> foundMap && right instanceof Map<?, ?> expectedMap) {
            if (foundMap.size() != expectedMap.size()) {
                return false;
            }
            for (Map.Entry<?, ?> entry : expectedMap.entrySet()) {
                if (!foundMap.containsKey(entry.getKey())
                        || !sameValue(foundMap.get(entry.getKey()), entry.getValue())) {
                    return false;
                }
            }
            return true;
        }
        if (left instanceof List<?> foundList && right instanceof List<?> expectedList) {
            if (foundList.size() != expectedList.size()) {
                return false;
            }
            for (int index = 0; index < foundList.size(); index++) {
                if (!sameValue(foundList.get(index), expectedList.get(index))) {
                    return false;
                }
            }
            return true;
        }
        if (left != null && left.equals(right)) {
            return true;
        }
        // The parser hands numbers back in its own type, so an int and a long that print the same still match.
        return left != null && right != null && String.valueOf(left).equals(String.valueOf(right));
    }

    private static int leadingSpaces(String line) {
        int spaces = 0;
        while (spaces < line.length() && line.charAt(spaces) == ' ') {
            spaces++;
        }
        return spaces;
    }
}
