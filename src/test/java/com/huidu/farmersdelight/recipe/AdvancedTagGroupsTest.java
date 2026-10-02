package com.huidu.farmersdelight.recipe;

import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Group resolution is what a pack author's intent turns into, and the failure modes are the interesting part:
 * a cycle or a typo must not quietly become "matches some of what was written", since the recipe that uses the
 * group would then match ingredients it never named.
 */
class AdvancedTagGroupsTest {

    private static Key group(String id) {
        return Key.of(id);
    }

    private static Map<String, List<String>> declared(Object... pairs) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], List.of(((String) pairs[i + 1]).split(",")));
        }
        return map;
    }

    @Test
    void aReferenceIsFlattenedIntoTheGroupThatUsesIt() {
        AdvancedTagGroups groups = AdvancedTagGroups.compile(declared(
                "meats", "minecraft:beef,minecraft:porkchop",
                "stew_meats", "advtag:meats,minecraft:chicken"));

        assertEquals(List.of(Key.of("minecraft:beef"), Key.of("minecraft:porkchop")), groups.members(group("meats")));
        assertEquals(List.of(Key.of("minecraft:beef"), Key.of("minecraft:porkchop"), Key.of("minecraft:chicken")),
                groups.members(group("stew_meats")));
        assertTrue(groups.dropped().isEmpty());
    }

    @Test
    void duplicatesAreCollapsedAndOrderKept() {
        AdvancedTagGroups groups = AdvancedTagGroups.compile(declared(
                "a", "minecraft:beef",
                "b", "minecraft:beef,minecraft:porkchop,advtag:a"));

        assertEquals(List.of(Key.of("minecraft:beef"), Key.of("minecraft:porkchop")), groups.members(group("b")));
    }

    @Test
    void membershipIgnoresCaseAndSurroundingSpace() {
        AdvancedTagGroups groups = AdvancedTagGroups.compile(declared("meats", "minecraft:beef"));

        assertTrue(groups.containsItem(group("meats"), "minecraft:beef"));
        assertTrue(groups.containsItem(group("meats"), "MINECRAFT:BEEF"));
        assertFalse(groups.containsItem(group("meats"), "minecraft:porkchop"));
        assertFalse(groups.containsItem(group("meats"), null));
        assertFalse(groups.containsItem(group("unknown"), "minecraft:beef"));
    }

    @Test
    void aCycleDropsEveryGroupInIt() {
        AdvancedTagGroups groups = AdvancedTagGroups.compile(declared(
                "a", "advtag:b",
                "b", "advtag:a"));

        assertTrue(groups.members(group("a")).isEmpty());
        assertTrue(groups.members(group("b")).isEmpty());
        assertEquals(2, groups.dropped().size(), "both halves of the cycle are unusable");
    }

    @Test
    void aSelfReferenceDropsTheGroup() {
        AdvancedTagGroups groups = AdvancedTagGroups.compile(declared("a", "minecraft:beef,advtag:a"));

        assertTrue(groups.members(group("a")).isEmpty());
        assertTrue(groups.dropped().contains(group("a")));
    }

    @Test
    void aReferenceToAnUnknownGroupDropsTheGroupThatMadeIt() {
        AdvancedTagGroups groups = AdvancedTagGroups.compile(declared(
                "known", "minecraft:beef",
                "broken", "advtag:typo,minecraft:porkchop"));

        assertTrue(groups.members(group("broken")).isEmpty(), "a partial match would be worse than none");
        assertTrue(groups.dropped().contains(group("broken")));
        assertEquals(List.of(Key.of("minecraft:beef")), groups.members(group("known")),
                "an unrelated group keeps working");
    }

    @Test
    void aChainDeeperThanTheLimitIsDroppedAsAWhole() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        int depth = AdvancedTagGroups.MAX_DEPTH + 2;
        for (int i = 0; i < depth; i++) {
            map.put("g" + i, List.of(i + 1 == depth ? "minecraft:beef" : "advtag:g" + (i + 1)));
        }
        AdvancedTagGroups groups = AdvancedTagGroups.compile(map);

        // The reference at the limit cannot resolve, so no group above it can mean what it says either.
        assertTrue(groups.members(group("g0")).isEmpty());
        assertTrue(groups.groups().isEmpty(), "the whole chain is unusable");
        assertFalse(groups.dropped().isEmpty());
    }

    @Test
    void aChainWithinTheLimitResolvesCompletely() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        int depth = AdvancedTagGroups.MAX_DEPTH;
        for (int i = 0; i < depth; i++) {
            map.put("g" + i, List.of(i + 1 == depth ? "minecraft:beef" : "advtag:g" + (i + 1)));
        }
        AdvancedTagGroups groups = AdvancedTagGroups.compile(map);

        assertEquals(List.of(Key.of("minecraft:beef")), groups.members(group("g0")));
        assertTrue(groups.dropped().isEmpty());
    }

    @Test
    void blankEntriesAndBlankNamesAreIgnored() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("meats", List.of("  ", "minecraft:beef", ""));
        map.put("  ", List.of("minecraft:porkchop"));

        AdvancedTagGroups groups = AdvancedTagGroups.compile(map);

        assertEquals(List.of(Key.of("minecraft:beef")), groups.members(group("meats")));
        assertEquals(1, groups.groups().size());
    }

    @Test
    void nothingDeclaredIsEmpty() {
        assertTrue(AdvancedTagGroups.compile(Map.of()).isEmpty());
        assertTrue(AdvancedTagGroups.compile(null).isEmpty());
        assertTrue(AdvancedTagGroups.EMPTY.isEmpty());
        assertTrue(AdvancedTagGroups.EMPTY.members(group("meats")).isEmpty());
    }
}
