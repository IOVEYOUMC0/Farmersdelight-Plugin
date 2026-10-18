package com.huidu.farmersdelight.util;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Tag expansion shared by bootstrap and runtime; it needs no loaded plugin or item registry. */
public final class CommonTagDefinitions {
    private CommonTagDefinitions() {}

    public static Map<String, Set<String>> expand(Map<String, Set<String>> raw, Set<String> problems) {
        Map<String, Set<String>> expanded = new HashMap<>();
        for (String tag : raw.keySet()) {
            expandTag(tag, raw, expanded, new HashSet<>(), new ArrayDeque<>(), problems);
        }
        return Map.copyOf(expanded);
    }

    public static String normalizeMember(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.startsWith("#")) {
            String tag = normalize(normalized.substring(1));
            return tag.isEmpty() ? "" : "#" + tag;
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static Set<String> expandTag(String tag, Map<String, Set<String>> rawTags,
                                         Map<String, Set<String>> expanded, Set<String> visiting,
                                         Deque<String> path, Set<String> problems) {
        Set<String> cached = expanded.get(tag);
        if (cached != null) {
            return cached;
        }
        if (!visiting.add(tag)) {
            problems.add("cycle:" + formatCycle(path, tag));
            return Set.of();
        }
        path.addLast(tag);
        Set<String> members = new HashSet<>();
        for (String member : rawTags.getOrDefault(tag, Set.of())) {
            if (member.startsWith("#")) {
                String referenced = member.substring(1);
                if (!rawTags.containsKey(referenced)) {
                    problems.add("missing:" + tag + " -> " + referenced);
                    continue;
                }
                members.addAll(expandTag(referenced, rawTags, expanded, visiting, path, problems));
            } else {
                members.add(member);
            }
        }
        path.removeLast();
        visiting.remove(tag);
        Set<String> frozen = Set.copyOf(members);
        expanded.put(tag, frozen);
        return frozen;
    }

    private static String formatCycle(Deque<String> path, String repeated) {
        StringBuilder result = new StringBuilder();
        boolean include = false;
        for (String tag : path) {
            if (tag.equals(repeated)) {
                include = true;
            }
            if (include) {
                if (result.length() > 0) {
                    result.append(" -> ");
                }
                result.append(tag);
            }
        }
        return result.append(" -> ").append(repeated).toString();
    }

    public static String normalize(String tagKey) {
        if (tagKey == null) {
            return "";
        }
        String normalized = tagKey.trim();
        if (normalized.startsWith("#")) {
            normalized = normalized.substring(1).trim();
        }
        return normalized.toLowerCase(Locale.ROOT);
    }
}
