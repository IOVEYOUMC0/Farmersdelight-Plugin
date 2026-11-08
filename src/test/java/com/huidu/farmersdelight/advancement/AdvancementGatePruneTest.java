package com.huidu.farmersdelight.advancement;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A malformed content pack costs the advancements that cannot reach the root, not the whole page.
 */
class AdvancementGatePruneTest {

    private static Map<String, String> parents(String... idThenParent) {
        Map<String, String> parents = new LinkedHashMap<>();
        for (int i = 0; i < idThenParent.length; i += 2) {
            parents.put(idThenParent[i], idThenParent[i + 1]);
        }
        return parents;
    }

    private static Set<String> kept(String... ids) {
        return new LinkedHashSet<>(java.util.List.of(ids));
    }

    @Test
    void aMisspelledParentIsPrunedWithItsSubtree() {
        Map<String, String> parents = parents("good", "root", "typo", "rooot", "child", "typo");
        Set<String> survivors = AdvancementGate.pruneUnreachable("root", parents, kept("root", "good", "typo", "child"));

        assertEquals(kept("root", "good"), survivors);
    }

    @Test
    void aCycleIsPruned() {
        Map<String, String> parents = parents("a", "b", "b", "a", "good", "root");
        Set<String> survivors = AdvancementGate.pruneUnreachable("root", parents, kept("root", "a", "b", "good"));

        assertEquals(kept("root", "good"), survivors);
    }

    @Test
    void aSecondRootIsPruned() {
        Map<String, String> parents = parents("good", "root");
        Set<String> survivors = AdvancementGate.pruneUnreachable("root", parents, kept("root", "good", "other-root"));

        assertEquals(kept("root", "good"), survivors);
        assertFalse(survivors.contains("other-root"));
    }

    @Test
    void anIntactTreeKeepsEveryNode() {
        Map<String, String> parents = parents("a", "root", "b", "a", "c", "b");
        Set<String> survivors = AdvancementGate.pruneUnreachable("root", parents, kept("root", "a", "b", "c"));

        assertEquals(kept("root", "a", "b", "c"), survivors);
    }

    @Test
    void aMissingRootSurvivesNothing() {
        assertTrue(AdvancementGate.pruneUnreachable("root", parents("a", "root"), kept("a")).isEmpty());
        assertNull(AdvancementGate.buildOrder("root", parents("a", "root"), kept("a")),
                "nothing to build without the root");
    }
}
