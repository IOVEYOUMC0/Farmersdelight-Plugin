package com.huidu.farmersdelight.api.migration;

import net.momirealms.craftengine.core.util.Key;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the legacy-id mapping rules and the replacement contract of {@link LegacyIdMigration}.
 *
 *
 * A test cannot build an ItemStack — {@code Material}'s static initialiser needs a running server — so the
 * mapping half is asserted through ids ({@link LegacyIdMigration#resolveId}) and the replacement half
 * through the {@code Bridge} seam: the stub records which CraftEngine/Bukkit calls the migration makes. The
 * two production lines the seam hides are the amount write and the persistent-data merge, both quoted in the
 * facility notes.
 */
class LegacyIdMigrationTest {

    private static final Key LEGACY = Key.of("farmersdelight:barbecue_stick");
    private static final Key CURRENT = Key.of("farmersdelight:cooked_meat_skewer");

    @AfterEach
    void reset() {
        LegacyIdMigration.clear();
        // null restores the production bridge, so no test can leave its stub installed for the next one.
        LegacyIdMigration.setBridge(null);
        LegacyIdMigration.setConflictReporter(null);
    }

    @Test
    void aNullBridgeRestoresTheProductionBridge() {
        LegacyIdMigration.setBridge(new StubBridge());
        assertFalse(LegacyIdMigration.usingProductionBridge());

        LegacyIdMigration.setBridge(null);

        assertTrue(LegacyIdMigration.usingProductionBridge(),
                "setBridge(null) has to put the production bridge back, not be a no-op");
    }

    @Test
    void aRegisteredIdResolvesToItsReplacement() {
        LegacyIdMigration.registerItem(LEGACY, CURRENT);

        assertEquals(CURRENT.toString(), LegacyIdMigration.resolveId(LEGACY.toString()));
        assertNull(LegacyIdMigration.resolveId("farmersdelight:tomato"), "an unregistered id stays unknown");
        assertEquals(1, LegacyIdMigration.size());
        assertFalse(LegacyIdMigration.isEmpty());
    }

    @Test
    void chainsResolveInOneStepAndTheResultIsNotLegacyAgain() {
        LegacyIdMigration.registerItem(Key.of("farmersdelight:a"), Key.of("farmersdelight:b"));
        LegacyIdMigration.registerItem(Key.of("farmersdelight:b"), Key.of("farmersdelight:c"));

        assertEquals("farmersdelight:c", LegacyIdMigration.resolveId("farmersdelight:a"));
        // Idempotence: the migrated id is not registered as legacy, so a second pass changes nothing.
        assertNull(LegacyIdMigration.resolveId("farmersdelight:c"));
    }

    @Test
    void aCycleTerminatesInsteadOfLooping() {
        LegacyIdMigration.registerItem(Key.of("farmersdelight:x"), Key.of("farmersdelight:y"));
        LegacyIdMigration.registerItem(Key.of("farmersdelight:y"), Key.of("farmersdelight:x"));

        String resolved = LegacyIdMigration.resolveId("farmersdelight:x");
        assertTrue("farmersdelight:x".equals(resolved) || "farmersdelight:y".equals(resolved),
                "the bounded chain has to return one of the cycle members, got " + resolved);
    }

    @Test
    void aSecondMappingForTheSameIdKeepsTheFirstAndIsReported() {
        List<String> reports = new ArrayList<>();
        LegacyIdMigration.setConflictReporter(reports::add);
        LegacyIdMigration.registerItem(LEGACY, CURRENT);
        LegacyIdMigration.registerItem(LEGACY, Key.of("farmersdelight:ham"));

        assertEquals(CURRENT.toString(), LegacyIdMigration.resolveId(LEGACY.toString()));
        assertEquals(1, LegacyIdMigration.conflictCount());
        assertEquals(1, reports.size());
        assertTrue(reports.getFirst().contains("already mapped"), reports.getFirst());
    }

    @Test
    void nullAndBlankInputsAreIgnored() {
        LegacyIdMigration.registerItem(null, CURRENT);
        LegacyIdMigration.registerItem(LEGACY, (Key) null);
        assertEquals(0, LegacyIdMigration.size());
        assertFalse(LegacyIdMigration.isLegacy(null));
        assertNull(LegacyIdMigration.migrate(null));
        assertNull(LegacyIdMigration.resolveId(null));
        assertNull(LegacyIdMigration.resolveId("   "));
    }

    @Test
    void theReplacementKeepsTheAmountAndMergesPersistentData() {
        LegacyIdMigration.registerItem(LEGACY, CURRENT);
        StubBridge bridge = new StubBridge();
        LegacyIdMigration.setBridge(bridge);

        ItemStack result = LegacyIdMigration.replace(CURRENT.toString(), null, 7, bridge);

        assertEquals(List.of("definition:" + CURRENT, "create:" + CURRENT, "amount:7", "merge"), bridge.calls,
                "the replacement has to be created from the current id, then get the old amount and the"
                        + " legacy persistent data");
        assertSame(bridge.created, result,
                "amount and persistent data have to be applied to the created replacement itself");
        // The landed values cannot be read back here: a bare ItemStack subclass can be constructed offline,
        // but setAmount/getType/getItemMeta delegate to the Craft implementation and throw without a server.
        // Verifying the amount and the PDC merge in the world is on the in-game checklist.
    }

    @Test
    void aSelfMappingIsIgnored() {
        LegacyIdMigration.registerItem(CURRENT, CURRENT);

        assertEquals(0, LegacyIdMigration.size(), "an id that maps to itself is not a migration");
        assertNull(LegacyIdMigration.resolveId(CURRENT.toString()));
        assertFalse(LegacyIdMigration.isLegacy(null));
    }

    @Test
    void aCreateThatReturnsNullLeavesTheStackAlone() {
        StubBridge bridge = new StubBridge();
        bridge.returnNull = true;
        LegacyIdMigration.setBridge(bridge);

        assertNull(LegacyIdMigration.replace("farmersdelight:raced", null, 5, bridge));
        assertEquals(List.of("definition:farmersdelight:raced", "create:farmersdelight:raced"), bridge.calls,
                "a null create must not reach the amount write or the data merge");
    }

    @Test
    void aReplacementWithoutADefinitionLeavesTheLegacyStackAlone() {
        StubBridge bridge = new StubBridge();
        bridge.hasDefinition = false;
        LegacyIdMigration.setBridge(bridge);

        assertNull(LegacyIdMigration.replace("farmersdelight:missing", null, 3, bridge));
        // The definition probe is expected to run; what must not happen is a write: no amount and no merge.
        assertTrue(bridge.calls.stream().noneMatch(call -> call.startsWith("amount") || call.equals("merge")),
                "a missing definition must not lead to an amount write or a data merge");
    }

    /**
     * Records the seam calls; the id table itself is what the other tests assert.
     *
     * <p>{@code create} hands out a bare {@link StubStack}, which is constructible without a server. Its
     * Craft-backed accessors are not usable offline (see the amount test), so it serves only as the object
     * the amount and the data merge are applied to.
     */
    private static final class StubBridge implements LegacyIdMigration.Bridge {

        private final List<String> calls = new ArrayList<>();
        private final StubStack created = new StubStack();
        private boolean hasDefinition = true;
        private boolean returnNull;

        @Override
        public String idOf(ItemStack stack) {
            return null;
        }

        @Override
        public boolean hasDefinition(String id) {
            calls.add("definition:" + id);
            return hasDefinition;
        }

        @Override
        public ItemStack create(String id) {
            calls.add("create:" + id);
            return returnNull ? null : created;
        }

        @Override
        public void setAmount(ItemStack stack, int amount) {
            calls.add("amount:" + amount);
        }

        @Override
        public void mergePersistentData(ItemStack legacy, ItemStack replacement) {
            calls.add("merge");
        }
    }

    /** Constructible without a server: {@code super()} does not touch the Craft delegate. */
    private static final class StubStack extends ItemStack {
    }
}
