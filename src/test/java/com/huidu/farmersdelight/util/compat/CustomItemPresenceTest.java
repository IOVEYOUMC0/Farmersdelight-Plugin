package com.huidu.farmersdelight.util.compat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The early-call guard: asking CraftEngine "is anything loaded" before it is enabled must answer false, never
 * throw — that NPE is what crashed an addon's onLoad on CraftEngine 26.9.2.
 */
class CustomItemPresenceTest {

    @AfterEach
    void restoreCraftEngineProbe() {
        CustomItemPresence.installProbe(null);
    }

    @Test
    void anUnavailableEngineAnswersFalseInsteadOfThrowing() {
        CustomItemPresence.installProbe(() -> {
            throw new NullPointerException("BukkitItemManager.instance() is null");
        });

        assertFalse(CustomItemPresence.anyLoaded(),
                "a not-yet-ready CraftEngine must not fail the caller: drop the guard and this throws");
    }

    @Test
    void aLinkageErrorFromAnOlderEngineIsAlsoTolerated() {
        CustomItemPresence.installProbe(() -> {
            throw new NoClassDefFoundError("net/momirealms/craftengine/bukkit/item/BukkitItemManager");
        });

        assertFalse(CustomItemPresence.anyLoaded(), "a missing class is 'not ready', not a crash");
    }

    @Test
    void aReadyEngineStillReportsItsItems() {
        CustomItemPresence.installProbe(() -> true);
        assertTrue(CustomItemPresence.anyLoaded());

        CustomItemPresence.installProbe(() -> false);
        assertFalse(CustomItemPresence.anyLoaded(), "an engine with no custom items is not loaded content");
    }

    @Test
    void theDefaultProbeIsRestored() {
        CustomItemPresence.installProbe(() -> true);
        CustomItemPresence.installProbe(null);
        // With no test double the CraftEngine question runs for real; no CraftEngine is running here, so the
        // only acceptable outcomes are "false" and "no exception".
        assertFalse(CustomItemPresence.anyLoaded());
    }

    // The probe reaches CraftEngine's item registry and one caller asks five times per block-physics event, so a
    // positive answer is remembered: drop the memo and this test sees five probe calls instead of one.
    @Test
    void aPositiveAnswerIsAskedOnce() {
        AtomicInteger probes = new AtomicInteger();
        CustomItemPresence.installProbe(() -> {
            probes.incrementAndGet();
            return true;
        });

        for (int call = 0; call < 5; call++) {
            assertTrue(CustomItemPresence.anyLoaded());
        }
        assertEquals(1, probes.get());
    }

    // A negative answer must keep being asked: CraftEngine enables after this plugin's onLoad, and a remembered
    // "no" would defer every registration that depends on this answer forever.
    @Test
    void aNegativeAnswerIsAskedAgainUntilItTurnsPositive() {
        AtomicBoolean ready = new AtomicBoolean();
        AtomicInteger probes = new AtomicInteger();
        CustomItemPresence.installProbe(() -> {
            probes.incrementAndGet();
            return ready.get();
        });

        assertFalse(CustomItemPresence.anyLoaded());
        assertFalse(CustomItemPresence.anyLoaded());

        ready.set(true);
        assertTrue(CustomItemPresence.anyLoaded(), "the answer has to flip once CraftEngine has items");
        assertEquals(3, probes.get());
    }

    // A CraftEngine reload can take the items away again: without the invalidate() call this keeps answering true
    // from the memo and the reload's empty registry is never seen.
    @Test
    void invalidateMakesTheNextCallAskAgain() {
        AtomicInteger probes = new AtomicInteger();
        CustomItemPresence.installProbe(() -> {
            probes.incrementAndGet();
            return probes.get() == 1;
        });

        assertTrue(CustomItemPresence.anyLoaded());
        assertTrue(CustomItemPresence.anyLoaded());
        assertEquals(1, probes.get());

        CustomItemPresence.invalidate();
        assertFalse(CustomItemPresence.anyLoaded(), "after a reload the probe decides again");
        assertEquals(2, probes.get());
    }

    // installProbe is the test seam and must also drop the answer the previous probe produced, or a test that
    // swaps the double would keep reading the old one.
    @Test
    void installingAProbeDropsTheRememberedAnswer() {
        CustomItemPresence.installProbe(() -> true);
        assertTrue(CustomItemPresence.anyLoaded());

        CustomItemPresence.installProbe(() -> false);
        assertFalse(CustomItemPresence.anyLoaded());
    }
}
