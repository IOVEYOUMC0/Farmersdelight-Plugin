package com.huidu.farmersdelight.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Periodic work that shares one period must not all start on the same tick: the delays are deterministic, spread
 * across the period, and distinct. Arming every task with delay 0 fails here.
 *
 *
 * The staggered sites are Bukkit tasks a unit test cannot instantiate, so the wiring is also read from the sources:
 * each of the sites that may be staggered has to arm its first tick through its own slot, while a site that must
 * start at once, or that drains a queue and cancels itself inside one tick, has to keep its literal delay and no
 * phase at all.
 */
class PeriodicStaggerTest {

    private static final long SHARED_PERIOD = 20L;

    private static final String[] SOURCE_ROOTS = {
            "FarmersDelight/src/main/java/com/huidu/farmersdelight/",
            "src/main/java/com/huidu/farmersdelight/"};

    @Test
    void everySlotStartsOnItsOwnTickInsideTheFirstPeriod() {
        List<Long> firstTicks = new ArrayList<>();
        for (String slot : PeriodicStagger.slots()) {
            firstTicks.add(PeriodicStagger.firstTick(slot, SHARED_PERIOD));
        }
        Set<Long> distinct = new HashSet<>(firstTicks);
        assertEquals(firstTicks.size(), distinct.size(), "each slot needs its own first tick: " + firstTicks);
        for (Long tick : firstTicks) {
            assertTrue(tick >= 1L && tick <= SHARED_PERIOD,
                    "the first tick stays inside the first period: " + tick);
        }
        assertEquals(1L, firstTicks.get(0), "the first slot keeps its immediate start");
    }

    @Test
    void theSpreadIsEvenAndDeterministic() {
        assertEquals(0L, PeriodicStagger.initialDelay("display-cull", SHARED_PERIOD));
        assertEquals(5L, PeriodicStagger.initialDelay("tick-cleanup", SHARED_PERIOD));
        assertEquals(10L, PeriodicStagger.initialDelay("display-sync", SHARED_PERIOD));
        assertEquals(15L, PeriodicStagger.initialDelay("carrier-restore", SHARED_PERIOD));
        assertEquals(PeriodicStagger.initialDelay("display-sync", SHARED_PERIOD),
                PeriodicStagger.initialDelay("display-sync", SHARED_PERIOD), "no randomness");
    }

    @Test
    void theThreeStaggeredSitesTakeThePhasesOfTheirOwnPeriod() {
        long cleanup = PeriodicStagger.initialDelay("tick-cleanup", 6000L);
        long sync = PeriodicStagger.initialDelay("display-sync", 20L);
        long carrier = PeriodicStagger.initialDelay("carrier-restore", 10L);
        assertEquals(1500L, cleanup, "the cleanup pass keeps its 6000 tick period and starts one quarter in");
        assertEquals(10L, sync, "the display sync keeps its 20 tick period and starts two quarters in");
        assertEquals(7L, carrier, "the carrier restorer keeps its 10 tick period and starts three quarters in");
        assertEquals(3, Set.of(cleanup, sync, carrier).size(),
                "three sites on three different ticks: " + List.of(cleanup, sync, carrier));
        assertEquals(6000L * 1L / 4L, cleanup, "phase one of four");
        assertEquals(20L * 2L / 4L, sync, "phase two of four");
        assertEquals(10L * 3L / 4L, carrier, "phase three of four");
        Set<Long> firstTicks = new HashSet<>();
        firstTicks.add(PeriodicStagger.firstTick("tick-cleanup", 6000L));
        firstTicks.add(PeriodicStagger.firstTick("display-sync", 20L));
        firstTicks.add(PeriodicStagger.firstTick("carrier-restore", 10L));
        assertEquals(3, firstTicks.size(), "and on three different first ticks: " + firstTicks);
    }

    @Test
    void theSameInputAlwaysGivesTheSamePhase() {
        for (String slot : PeriodicStagger.slots()) {
            assertEquals(PeriodicStagger.initialDelay(slot, 37L), PeriodicStagger.initialDelay(slot, 37L),
                    slot + " has a fixed phase");
            assertEquals(PeriodicStagger.firstTick(slot, 37L), PeriodicStagger.firstTick(slot, 37L),
                    slot + " has a fixed first tick");
        }
    }

    @Test
    void theStaggeredSitesArmThroughTheirOwnSlot() throws IOException {
        assertTrue(flat("manager/TickManager.java").contains("runRepeating(this::performCleanup,"
                        + " PeriodicStagger.initialDelay(\"tick-cleanup\", CLEANUP_INTERVAL), CLEANUP_INTERVAL)"),
                "the cleanup pass has to reach its first tick through its own slot and keep its period");
        assertTrue(flat("visual/ProxyItemDisplayManager.java").contains("runRepeating(this::syncAll,"
                        + " PeriodicStagger.initialDelay(\"display-sync\", syncIntervalTicks), syncIntervalTicks)"),
                "the display sync has to reach its first tick through its own slot and keep its period");
        assertTrue(flat("listener/CarrierRestoreListener.java").contains("runRepeating(restorer::tick,"
                        + " PeriodicStagger.initialDelay(\"carrier-restore\", interval), interval)"),
                "the carrier restorer has to reach its first tick through its own slot and keep its period");
        assertTrue(flat("visual/RealDisplayCuller.java")
                        .contains("PeriodicStagger.initialDelay(\"display-cull\", DEFAULT_INTERVAL_TICKS),"
                                + " DEFAULT_INTERVAL_TICKS)"),
                "the culling pass stays on the first slot, the one that keeps its immediate start");
    }

    @Test
    void theSitesThatMustStartAtOnceKeepTheirLiteralDelay() throws IOException {
        assertTrue(flat("manager/TickManager.java").contains("runRepeating(this::tick, 1L, TICK_INTERVAL)"),
                "the cooking pot tick keeps its first tick");
        assertTrue(flat("manager/StoveManager.java").contains("runRepeating(this::tick, 1L, STOVE_TICK_INTERVAL)"),
                "a stove re-arms on the tick the block was placed, so a delayed first tick moves real cooking time");
        assertTrue(flat("manager/SkilletManager.java").contains("runRepeating(this::tick, 1L, PLACED_TICK_INTERVAL)"),
                "a placed skillet advances on the tick it appears");
        assertTrue(flat("manager/BuffBossbarManager.java").contains("runRepeating(this::tick, 1L, 1L)"),
                "the bar rotation is a per tick animation");
        assertTrue(flat("manager/SkilletHandheldCooking.java").contains("runRepeating(this::tickHandheld, 1L, 1L)"),
                "handheld cooking advances one tick per call");
        assertTrue(flat("handheld/HandCookedSkewerHooks.java").contains("runRepeating(tick, 0, 1)"),
                "the held skewer progress starts on the tick the session begins");
        assertTrue(flat("effect/EffectListener.java").contains("runRepeating(CustomBuffRegistry::syncTrackedPlayers,"
                        + " BUFF_SYNC_INTERVAL_TICKS, BUFF_SYNC_INTERVAL_TICKS)"),
                "the buff sync keeps its first tick");
        assertTrue(flat("effect/EffectListener.java").contains("runRepeating(this::tickEffects, 1L, tickInterval)"),
                "effect durations are counted per tick");
        assertTrue(flat("gui/GuiTickManager.java").contains("runRepeating(() -> {"),
                "a GUI callback scheduled for this tick must not slip");
        assertTrue(flat("listener/ChunkLoadListener.java").contains("runRepeating(() -> {"),
                "the startup chunk pass is ordered against the load it follows");
        assertTrue(flat("FarmersDelightPlugin.java").contains("runRepeating(tick, 1, 1)"),
                "recipe registration is one pass of single tick steps with no phase space");
    }

    @Test
    void theOneShotDrainsKeepTheirPerTickStart() throws IOException {
        assertTrue(flat("block/behavior/CuttingBoardBlockBehavior.java")
                        .contains("CuttingBoardBlockBehavior::refreshNextDisplayEntities, 1L, 1L)"),
                "the cutting board refresh drains its queue and cancels itself, so it has no period to be staggered"
                        + " inside");
        assertTrue(flat("manager/SkilletManager.java").contains("runRepeating(this::refreshNextVisuals, 1L, 1L)"),
                "the skillet visual drain has the same shape and keeps the same start");
    }

    @Test
    void theSitesThatMustStartAtOnceCarryNoPhaseAtAll() throws IOException {
        String[] untouched = {
                "manager/StoveManager.java",
                "manager/SkilletManager.java",
                "manager/BuffBossbarManager.java",
                "manager/SkilletHandheldCooking.java",
                "handheld/HandCookedSkewerHooks.java",
                "effect/EffectListener.java",
                "gui/GuiTickManager.java",
                "listener/ChunkLoadListener.java",
                "block/behavior/CuttingBoardBlockBehavior.java",
                "FarmersDelightPlugin.java"};
        for (String relative : untouched) {
            assertFalse(flat(relative).contains("initialDelay("),
                    relative + " must keep its literal first delay: a phase here moves gameplay timing or delays a"
                            + " one-shot drain that has no period to spread over");
        }
    }

    @Test
    void anUnknownSlotIsRejectedInsteadOfSilentlyStartingAtZero() {
        assertThrows(IllegalArgumentException.class, () -> PeriodicStagger.initialDelay("not-a-slot", SHARED_PERIOD));
    }

    private static String flat(String relative) throws IOException {
        Path found = locate(relative);
        if (found == null) {
            throw new AssertionError("source has to be reachable from the test working directory: " + relative);
        }
        return Files.readString(found).replaceAll("\\s+", " ");
    }

    private static Path locate(String relative) {
        Path cursor = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 4 && cursor != null; depth++) {
            for (String root : SOURCE_ROOTS) {
                Path candidate = cursor.resolve(root + relative);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
            cursor = cursor.getParent();
        }
        return null;
    }
}
