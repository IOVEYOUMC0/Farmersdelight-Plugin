package com.huidu.farmersdelight.handheld;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the two rules the handheld skewer depends on: the heat check reads only owned positions and
 * dispatches every foreign one exactly once, and cooking takes 120 ticks and consumes exactly one skewer.
 */
class HandCookedSkewerTest {

    // ── Heat probe ────────────────────────────────────────────────────────

    @Test
    void aPlayerOnFireIsNearAHeatSourceWithoutReadingAnyBlock() {
        FakeRegionAccess access = new FakeRegionAccess();
        access.onFire = true;

        assertTrue(SkewerHeatProbe.isNearHeatSource(access, 0, 64, 0));
        assertEquals(0, access.reads.size(), "being on fire needs no block read at all");
        assertEquals(0, access.dispatches.size());
    }

    @Test
    void aHeatSourceInsideTheCubeIsFoundWithNoDispatch() {
        FakeRegionAccess access = new FakeRegionAccess();
        access.heat.add(key(1, 64, 0));

        assertTrue(SkewerHeatProbe.isNearHeatSource(access, 0, 64, 0));
        assertEquals(0, access.dispatches.size(),
                "a player fully inside its own region must not cause a single dispatch");
    }

    @Test
    void anEmptyCubeIsNotNearAHeatSource() {
        FakeRegionAccess access = new FakeRegionAccess();

        assertFalse(SkewerHeatProbe.isNearHeatSource(access, 0, 64, 0));
        assertFalse(SkewerHeatProbe.isNearHeatSource(null, 0, 64, 0), "no access means no answer");
    }

    @Test
    void aForeignHeatBlockIsReadThroughExactlyOneDispatch() {
        FakeRegionAccess access = new FakeRegionAccess();
        // A player at a region edge: only this one column belongs to another region, and it holds the heat.
        access.foreign.add(column(1, 1));
        access.heat.add(key(1, 64, 1));

        assertTrue(SkewerHeatProbe.isNearHeatSource(access, 0, 64, 0));
        assertEquals(1, access.dispatches.size(), "exactly one hand-off to the owning region");
        assertEquals(column(1, 1), access.dispatches.getFirst());
    }

    @Test
    void aForeignPositionIsNeverReadInline() {
        FakeRegionAccess access = new FakeRegionAccess();
        access.foreign.add(column(1, 1));

        SkewerHeatProbe.isNearHeatSource(access, 0, 64, 0);

        for (String read : access.inlineReads) {
            assertFalse(access.foreign.contains(columnOf(read)),
                    "a foreign position was read inline: " + read);
        }
        assertTrue(access.dispatches.contains(column(1, 1)),
                "the foreign column has to be handed to its owner: " + access.dispatches);
        assertFalse(access.dispatchedReads.isEmpty(),
                "the foreign column's three heights are read by the dispatched task");
        assertFalse(access.inlineReads.isEmpty(), "the owned columns are still read inline");
    }

    // ── Cooking session ──────────────────────────────────────────────────

    @Test
    void oneHundredTwentyTicksCookExactlyOneSkewer() {
        AtomicInteger cooked = new AtomicInteger();
        HandCookedSkewerService service = new HandCookedSkewerService(true, null, player -> cooked.incrementAndGet());
        UUID player = UUID.randomUUID();

        assertTrue(service.tryStart(player));
        for (int tick = 1; tick < HandCookedSkewerService.COOKING_TICKS; tick++) {
            assertFalse(service.tick(player), "tick " + tick + " must not finish the skewer yet");
            assertEquals(0, cooked.get());
        }

        assertTrue(service.tick(player), "the 120th tick finishes the skewer");
        assertEquals(1, cooked.get(), "exactly one skewer per session");
        assertFalse(service.isCooking(player), "the session is cleared");
        assertEquals(-1, service.remainingTicks(player));

        assertFalse(service.tick(player), "a tick without a session must not cook anything");
        assertEquals(1, cooked.get());
    }

    @Test
    void theCookingTimeComesFromTheConfiguration() {
        AtomicInteger cooked = new AtomicInteger();
        HandCookedSkewerService service = new HandCookedSkewerService(true, () -> 20, player -> cooked.incrementAndGet());
        UUID player = UUID.randomUUID();

        assertTrue(service.tryStart(player));
        for (int tick = 0; tick < 19; tick++) {
            service.tick(player);
        }
        assertEquals(0, cooked.get());
        assertTrue(service.tick(player));
        assertEquals(1, cooked.get());
    }

    @Test
    void everyInterruptCancelsWithZeroProgress() {
        // Released key, slot change, hand swap, drop, quit and death all end in the same call.
        AtomicInteger cooked = new AtomicInteger();
        HandCookedSkewerService service = new HandCookedSkewerService(true, null, player -> cooked.incrementAndGet());
        UUID player = UUID.randomUUID();

        assertTrue(service.tryStart(player));
        for (int tick = 0; tick < 60; tick++) {
            service.tick(player);
        }
        assertTrue(service.cancel(player), "the interrupt has to end the session");
        assertFalse(service.isCooking(player));
        assertEquals(-1, service.remainingTicks(player), "progress is dropped, not paused");

        for (int tick = 0; tick < HandCookedSkewerService.COOKING_TICKS; tick++) {
            service.tick(player);
        }
        assertEquals(0, cooked.get(), "an interrupted session must never cook");
        assertFalse(service.cancel(player), "cancelling twice is harmless");
    }

    @Test
    void aPlayerCooksOnlyOneThingAtATime() {
        // This is the mutual exclusion against every other handheld cooking path, the skillet included.
        HandCookedSkewerService service = new HandCookedSkewerService(true, null, player -> {
        });
        UUID player = UUID.randomUUID();

        assertTrue(service.tryStart(player));
        assertFalse(service.tryStart(player), "a second start while cooking has to be refused");
        service.cancel(player);
        assertTrue(service.tryStart(player), "after the interrupt the player may start again");
    }

    @Test
    void aDisabledPathNeverStartsAndCancelsWhatIsRunning() {
        HandCookedSkewerService service = new HandCookedSkewerService(false, null, player -> {
        });
        UUID player = UUID.randomUUID();

        assertFalse(service.tryStart(player), "disabled: the handheld path must not start");

        service.setEnabled(true);
        assertTrue(service.tryStart(player));
        service.setEnabled(false);
        assertFalse(service.isCooking(player), "turning it off has to end running sessions");
        assertFalse(service.tryStart(player));
    }

    private static String key(int x, int y, int z) {
        return x + "|" + y + "|" + z;
    }

    /** The column part of an "x|y|z" read key, so a read maps back to its column. */
    private static String columnOf(String read) {
        String[] parts = read.split("\\|");
        return parts.length < 3 ? read : parts[0] + "|" + parts[2];
    }

    private static String column(int x, int z) {
        return x + "|" + z;
    }

    /** A region seam over a plain map: which columns are foreign, which blocks are heat sources. */
    private static final class FakeRegionAccess implements SkewerHeatProbe.RegionAccess {

        private final List<String> foreign = new ArrayList<>();
        private final List<String> heat = new ArrayList<>();
        private final List<String> reads = new ArrayList<>();
        // Split on purpose: a read recorded while a dispatched task runs is not an inline read, so the
        // "never read inline" assertion cannot be satisfied by the dispatch itself.
        private final List<String> inlineReads = new ArrayList<>();
        private final List<String> dispatchedReads = new ArrayList<>();
        private final List<String> dispatches = new ArrayList<>();
        private boolean onFire;
        private boolean inDispatch;

        @Override
        public boolean owns(int blockX, int blockZ) {
            return !foreign.contains(column(blockX, blockZ));
        }

        @Override
        public boolean isHeatSource(int blockX, int blockY, int blockZ) {
            String read = key(blockX, blockY, blockZ);
            reads.add(read);
            (inDispatch ? dispatchedReads : inlineReads).add(read);
            return heat.contains(read);
        }

        @Override
        public void runAt(int blockX, int blockZ, Runnable read) {
            dispatches.add(column(blockX, blockZ));
            inDispatch = true;
            try {
                read.run();
            } finally {
                inDispatch = false;
            }
        }

        @Override
        public boolean isOnFire() {
            return onFire;
        }
    }
}
