package com.huidu.farmersdelight.migration;

import org.bukkit.Location;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the Folia rule the migration hooks share: work runs inline when the current thread already owns the
 * target, and is dispatched exactly once when it does not.
 *
 *
 * The dispatch is injected, so the decision is assertable without a region scheduler: the fake counts the
 * hand-offs and records whether the task body ran where it was called. owner is an opaque token here
 * because the production owner is a Location or an Entity, neither of which can be built offline.
 */
class MigrationDispatchSeamTest {

    @Test
    void aTaskForTheOwnedThreadRunsInlineWithNoDispatch() {
        RecordingDispatch dispatch = new RecordingDispatch(true);
        AtomicInteger ran = new AtomicInteger();

        MigrationDispatch.inlineIfOwned(dispatch, "owner", ran::incrementAndGet);

        assertEquals(1, ran.get(), "the work still has to run");
        assertEquals(0, dispatch.dispatches.size(), "an owned target must not be handed to the scheduler");
        assertTrue(dispatch.globalCalls == 0);
    }

    @Test
    void aTaskForAnotherRegionIsDispatchedExactlyOnce() {
        RecordingDispatch dispatch = new RecordingDispatch(false);
        AtomicInteger ran = new AtomicInteger();

        MigrationDispatch.inlineIfOwned(dispatch, "owner", ran::incrementAndGet);

        assertEquals(List.of("owner"), dispatch.dispatches, "exactly one hand-off, to the owning region");
        assertEquals(0, ran.get(), "the body must not run on a thread that does not own the target");
        dispatch.runRecorded();
        assertEquals(1, ran.get(), "the dispatched task runs once on the owning thread");
    }

    @Test
    void anUnknownOwnerIsNeverTouched() {
        RecordingDispatch dispatch = new RecordingDispatch(false);
        AtomicInteger ran = new AtomicInteger();

        MigrationDispatch.inlineIfOwned(dispatch, null, ran::incrementAndGet);

        assertEquals(0, ran.get());
        assertTrue(dispatch.dispatches.isEmpty(), "work with no owner cannot be attributed to a region");
    }

    @Test
    void aMissingDispatcherSkipsTheWorkInsteadOfCrashing() {
        AtomicInteger ran = new AtomicInteger();
        MigrationDispatch.inlineIfOwned(null, "owner", ran::incrementAndGet);
        MigrationDispatch.global(null, ran::incrementAndGet);
        assertEquals(0, ran.get());
    }

    @Test
    void theStartupSweepGoesThroughTheGlobalThread() {
        RecordingDispatch dispatch = new RecordingDispatch(true);
        AtomicInteger ran = new AtomicInteger();

        MigrationDispatch.global(dispatch, ran::incrementAndGet);

        assertEquals(1, ran.get());
        assertEquals(1, dispatch.globalCalls);
        assertTrue(dispatch.dispatches.isEmpty());
    }

    @Test
    void theHooksAreGatedByConfigAndByAnEmptyTable() {
        assertTrue(LegacyIdMigrationService.hooksAllowed(true, false), "on with a registered id: run");
        assertFalse(LegacyIdMigrationService.hooksAllowed(false, false), "switched off in config: skip");
        assertFalse(LegacyIdMigrationService.hooksAllowed(true, true),
                "an empty table is the zero-cost fast path: nothing to do");
        assertFalse(LegacyIdMigrationService.hooksAllowed(false, true));
    }

    @Test
    void anUnknownNonNullHolderIsSkippedInsteadOfFallingBackToTheViewer() {
        // A holder type this hook does not know: not a BlockState, not a DoubleChest, not the plugin's GUI, not a
        // player. It must not be migrated through some fallback owner.
        InventoryHolder unknown = new InventoryHolder() {
            @Override
            public Inventory getInventory() {
                return null;
            }
        };
        RecordingDispatch dispatch = new RecordingDispatch(false);
        AtomicInteger ran = new AtomicInteger();

        Location owner = LegacyIdMigrationHooks.ownerOf(unknown, null);

        assertNull(owner, "an unknown holder has no owner");
        MigrationDispatch.inlineIfOwned(dispatch, owner, ran::incrementAndGet);
        assertEquals(0, ran.get(), "the container must not be touched");
        assertTrue(dispatch.dispatches.isEmpty(), "zero dispatches: the open is skipped, not retried elsewhere");
        assertNull(LegacyIdMigrationHooks.ownerOf(null, null), "a null holder has no owner either");
    }

    @Test
    void eachOwnerTokenIsDispatchedOnceToItself() {
        // The seam routes each owner to exactly one dispatch and keeps the target. The DoubleChest branch of
        // ownerOf cannot be exercised offline: DoubleChest only has a constructor taking a
        // DoubleChestInventory, and a Location needs a World. That branch is on the in-game checklist.
        RecordingDispatch dispatch = new RecordingDispatch(false);
        AtomicInteger ran = new AtomicInteger();
        Object leftHalf = new Object();
        Object rightHalf = new Object();

        MigrationDispatch.inlineIfOwned(dispatch, leftHalf, ran::incrementAndGet);
        MigrationDispatch.inlineIfOwned(dispatch, rightHalf, ran::incrementAndGet);

        assertEquals(2, dispatch.dispatches.size(), "one dispatch per opened container half");
        assertSame(leftHalf, dispatch.dispatches.get(0), "the left half is dispatched to its own owner");
        assertSame(rightHalf, dispatch.dispatches.get(1), "the right half is dispatched to its own owner");
        assertEquals(0, ran.get(), "neither half runs on the calling thread");
    }

    @Test
    void theOpenDedupeRecordsEachKeyOnce() {
        OpenDedupe dedupe = new OpenDedupe();
        assertTrue(dedupe.firstOpen("chest"));
        assertFalse(dedupe.firstOpen("chest"), "a second click must not rescan the open container");
        assertFalse(dedupe.firstOpen(null), "a null key is never first");
        assertEquals(1, dedupe.size());
    }

    @Test
    void theOpenDedupeSurvivesConcurrentOpens() throws InterruptedException {
        OpenDedupe dedupe = new OpenDedupe();
        int threads = 8;
        int perThread = 250;
        // The keys are held strongly on purpose. OpenDedupe is a weak map by design (a closed inventory has to
        // stay collectable), so a key only the map can reach may be collected mid-run and then look like a fresh
        // open again — which is what made this test fail with more "firsts" than containers. Real keys are
        // Inventory instances, which the server holds for the whole open; the test holds its keys the same way,
        // otherwise it measures the garbage collector instead of the dedupe.
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < perThread; i++) {
            keys.add("container-" + i);
        }
        List<Thread> workers = new ArrayList<>();
        AtomicInteger firsts = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    if (dedupe.firstOpen(keys.get(i))) {
                        firsts.incrementAndGet();
                    }
                }
            });
            workers.add(worker);
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join();
        }

        assertEquals(perThread, firsts.get(), "every container has to be a first open exactly once");
        assertEquals(perThread, dedupe.size(), "concurrent adds must not lose entries");
    }

    /** Records the hand-offs instead of touching a real scheduler. */
    private static final class RecordingDispatch implements MigrationDispatch {

        private final boolean owns;
        private final List<Object> dispatches = new ArrayList<>();
        private final List<Runnable> queued = new ArrayList<>();
        private int globalCalls;

        RecordingDispatch(boolean owns) {
            this.owns = owns;
        }

        void runRecorded() {
            List<Runnable> pending = new ArrayList<>(queued);
            queued.clear();
            pending.forEach(Runnable::run);
        }

        @Override
        public boolean ownsCurrentThread(Object owner) {
            return owns;
        }

        @Override
        public void runAtOwner(Object owner, Runnable task) {
            dispatches.add(owner);
            queued.add(task);
        }

        @Override
        public void runGlobal(Runnable task) {
            globalCalls++;
            task.run();
        }
    }
}
