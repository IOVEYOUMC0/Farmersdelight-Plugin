package com.huidu.farmersdelight.recipe;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class DiscoveryPendingLocksTest {
    @Test void aLockDuringTheInitialDiskReadIsNotResurrectedByOldData() {
        Set<String> stored = Set.of("pot bread", "board fish");
        assertEquals(Set.of("board fish"), RecipeDiscoveryManager.mergeStoredUnlocks(stored, Set.of("pot bread")));
        assertEquals(Set.of("pot bread", "board fish"), stored);
    }

    @Test void anUnknownLockDoesNotRemoveUnrelatedUnlocks() {
        assertEquals(Set.of("pot bread"),
                RecipeDiscoveryManager.mergeStoredUnlocks(Set.of("pot bread"), Set.of("board fish")));
        assertEquals(Set.of(), RecipeDiscoveryManager.mergeStoredUnlocks(Set.of(), Set.of("pot bread")));
    }

    @Test void anOlderFlushMustNotOverwriteANewerOne() {
        assertTrue(RecipeDiscoveryManager.isNewerSnapshot(1, 0), "the first flush has nothing written before it");
        assertTrue(RecipeDiscoveryManager.isNewerSnapshot(2, 1));
        assertFalse(RecipeDiscoveryManager.isNewerSnapshot(1, 2), "a stale snapshot must be dropped, not written");
        assertFalse(RecipeDiscoveryManager.isNewerSnapshot(1, 1), "this sequence already committed");
    }
}
