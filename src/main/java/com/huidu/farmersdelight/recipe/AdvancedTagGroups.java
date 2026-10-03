package com.huidu.farmersdelight.recipe;

import net.momirealms.craftengine.core.util.Key;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The advanced tag groups a content pack declares, resolved once into flat member lists.
 *
 *
 * A group lists item ids, and an entry written advtag:<group> pulls in another group's members.
 * Resolution happens at load time, so a lookup during recipe matching is a map read instead of a walk over
 * nested references: callers get the flattened list and a lower-cased index of the same members.
 *
 *
 * A group whose references cannot be resolved is dropped whole rather than partially - a cycle, a
 * reference to a group that was never declared, or nesting past MAX_DEPTH. A partially resolved
 * group would silently match a subset of what it was written to mean, which is worse than not matching at
 * all; the dropped ids are kept so an operator can be told which definition was ignored.
 */
public final class AdvancedTagGroups {

    public static final AdvancedTagGroups EMPTY = new AdvancedTagGroups(Map.of(), Set.of());

    /** Deepest reference chain that is still considered a definition rather than a mistake. */
    static final int MAX_DEPTH = 128;

    private static final String REFERENCE_PREFIX = "advtag:";

    private final Map<Key, List<Key>> members;
    private final Map<Key, Set<String>> memberIds;
    private final Set<Key> dropped;

    private AdvancedTagGroups(Map<Key, List<Key>> members, Set<Key> dropped) {
        this.members = Map.copyOf(members);
        Map<Key, Set<String>> index = new LinkedHashMap<>(members.size());
        for (Map.Entry<Key, List<Key>> entry : members.entrySet()) {
            Set<String> ids = new LinkedHashSet<>(entry.getValue().size());
            for (Key member : entry.getValue()) {
                ids.add(member.toString().toLowerCase(Locale.ROOT));
            }
            index.put(entry.getKey(), Set.copyOf(ids));
        }
        this.memberIds = Map.copyOf(index);
        this.dropped = Set.copyOf(dropped);
    }

    /**
     * Resolves every declared group.
     *
     * @param declared group id to its raw entries: item ids, or advtag:<group> references
     */
    public static AdvancedTagGroups compile(Map<String, ? extends Collection<String>> declared) {
        if (declared == null || declared.isEmpty()) {
            return EMPTY;
        }
        Map<Key, List<String>> raw = new LinkedHashMap<>(declared.size());
        for (Map.Entry<String, ? extends Collection<String>> entry : declared.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            List<String> entries = new ArrayList<>();
            Collection<String> values = entry.getValue();
            if (values != null) {
                for (String value : values) {
                    if (value != null && !value.isBlank()) {
                        entries.add(value.trim());
                    }
                }
            }
            raw.put(Key.of(entry.getKey().trim()), entries);
        }

        Map<Key, List<Key>> resolved = new LinkedHashMap<>(raw.size());
        Set<Key> dropped = new LinkedHashSet<>();
        for (Key group : raw.keySet()) {
            if (!resolved.containsKey(group) && !dropped.contains(group)) {
                resolve(group, raw, resolved, dropped, new ArrayDeque<>(), 0);
            }
        }
        return resolved.isEmpty() && dropped.isEmpty() ? EMPTY : new AdvancedTagGroups(resolved, dropped);
    }

    /**
     * Resolves one group into resolved, or records it (and therefore everything that referenced it)
     * as dropped. Returns null when the group could not be resolved.
     */
    private static List<Key> resolve(Key group, Map<Key, List<String>> raw, Map<Key, List<Key>> resolved,
                                     Set<Key> dropped, Deque<Key> path, int depth) {
        List<Key> cached = resolved.get(group);
        if (cached != null) {
            return cached;
        }
        if (depth > MAX_DEPTH || path.contains(group)) {
            dropped.add(group);
            return null;
        }
        List<String> entries = raw.get(group);
        if (entries == null) {
            dropped.add(group);
            return null;
        }

        path.addLast(group);
        try {
            Set<Key> flattened = new LinkedHashSet<>();
            for (String entry : entries) {
                if (!isReference(entry)) {
                    flattened.add(Key.of(entry));
                    continue;
                }
                Key referenced = Key.of(entry.substring(REFERENCE_PREFIX.length()));
                List<Key> nested = resolve(referenced, raw, resolved, dropped, path, depth + 1);
                if (nested == null) {
                    // The reference does not resolve, so this group cannot mean what it says either.
                    dropped.add(group);
                    return null;
                }
                flattened.addAll(nested);
            }
            List<Key> result = List.copyOf(flattened);
            resolved.put(group, result);
            return result;
        } finally {
            path.removeLast();
        }
    }

    private static boolean isReference(String entry) {
        return entry.length() > REFERENCE_PREFIX.length()
                && entry.regionMatches(true, 0, REFERENCE_PREFIX, 0, REFERENCE_PREFIX.length());
    }

    /** The flattened members of a group, or an empty list when the group is unknown or was dropped. */
    public List<Key> members(Key group) {
        return this.members.getOrDefault(group, List.of());
    }

    /** Whether a group contains an item, comparing the way item ids are written rather than by case. */
    public boolean containsItem(Key group, String itemId) {
        if (group == null || itemId == null || itemId.isBlank()) {
            return false;
        }
        Set<String> ids = this.memberIds.get(group);
        return ids != null && ids.contains(itemId.trim().toLowerCase(Locale.ROOT));
    }

    public Set<Key> groups() {
        return this.members.keySet();
    }

    /** Groups that were declared but could not be resolved, in declaration order. */
    public Set<Key> dropped() {
        return this.dropped;
    }

    public boolean isEmpty() {
        return this.members.isEmpty() && this.dropped.isEmpty();
    }
}
