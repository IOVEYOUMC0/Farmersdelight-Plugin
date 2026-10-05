package com.huidu.farmersdelight.visual;

import com.huidu.farmersdelight.util.scheduler.PluginTask;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The culling policy both display paths share: an invisible viewer gets range 0 (the entity stays), a visible
 * one gets the base range, and a pass only sends when the answer changed.
 *
 *
 * The glue that turns this into packets needs a server, but every decision it makes goes through here, so the
 * interesting failures (no culling at all, no restore, a packet per pass) are covered offline.
 */
class DisplayCullingTest {

    @Test
    void anInvisibleViewerIsToldZeroAndKeepsItsEntity() {
        assertEquals(0.0F, DisplayCulling.rangeFor(false, 1.0F), 0.0F,
                "culling a viewer means range 0, never removing the entity");
        assertEquals(0.0F, DisplayCulling.rangeFor(false, 4.0F), 0.0F,
                "whatever the base range is, culled is culled");
    }

    @Test
    void aVisibleViewerGetsTheBaseRange() {
        assertEquals(1.0F, DisplayCulling.rangeFor(true, 1.0F), 0.0F, "CE default range");
        assertEquals(2.5F, DisplayCulling.rangeFor(true, 2.5F), 0.0F, "scaled range is passed through");
        assertEquals(0.0F, DisplayCulling.rangeFor(true, -1.0F), 0.0F, "a negative base cannot happen");
    }

    @Test
    void theRangeOnlyGoesOutWhenItChanges() {
        DisplayCulling.ViewRangeState state = new DisplayCulling.ViewRangeState();
        assertFalse(state.hasSent(), "nothing sent yet");

        assertEquals(1.0F, state.update(true, 1.0F), 0.0F, "first sight sends the base range");
        assertTrue(Float.isNaN(state.update(true, 1.0F)), "and an unchanged pass sends nothing");
        assertEquals(0.0F, state.update(false, 1.0F), 0.0F, "leaving sends 0");
        assertTrue(Float.isNaN(state.update(false, 1.0F)), "staying culled sends nothing");
        assertEquals(1.0F, state.update(true, 1.0F), 0.0F, "coming back restores the base range");
        assertEquals(1.0F, state.lastSent(), 0.0F, "and the state remembers it");
    }

    @Test
    void hysteresisKeepsAViewerAtTheBoundaryFromFlapping() {
        double viewDistance = 64.0D;
        double atBoundary = 66.0D * 66.0D;

        assertFalse(DisplayCulling.isVisible(atBoundary, viewDistance, 0.0D),
                "66 blocks is outside a 64 block view distance");
        assertTrue(DisplayCulling.isVisible(atBoundary, viewDistance, 8.0D),
                "but a viewer that already sees it keeps it inside the hysteresis margin");
        assertFalse(DisplayCulling.isVisible(73.0D * 73.0D, viewDistance, 8.0D),
                "beyond the margin it really is culled");
        assertTrue(DisplayCulling.isVisible(64.0D * 64.0D, viewDistance, 0.0D), "the boundary itself is in");
        assertFalse(DisplayCulling.isVisible(64.01D * 64.01D, viewDistance, 0.0D), "just past it is out");
    }

    /** Two viewers of the same display never share state: culling one must not touch the other. */
    @Test
    void twoViewersKeepIndependentRangeStates() {
        DisplayCulling.ViewRangeState near = new DisplayCulling.ViewRangeState();
        DisplayCulling.ViewRangeState far = new DisplayCulling.ViewRangeState();

        assertEquals(1.0F, near.update(true, 1.0F), 0.0F);
        assertEquals(1.0F, far.update(true, 1.0F), 0.0F);

        assertEquals(0.0F, far.update(false, 1.0F), 0.0F, "the far viewer is culled");
        assertTrue(Float.isNaN(near.update(true, 1.0F)), "the near viewer saw no change at all");
        assertEquals(1.0F, near.lastSent(), 0.0F, "and keeps its base range");

        assertEquals(1.0F, far.update(true, 1.0F), 0.0F, "the far viewer comes back on its own");
    }

    /** Walking 64 -> 66 -> 73 -> 66 blocks must not oscillate: exactly three changes, none repeated. */
    @Test
    void aBoundaryRoundTripChangesTheRangeExactlyThreeTimes() {
        DisplayCulling.ViewRangeState state = new DisplayCulling.ViewRangeState();
        assertEquals(1.0F, state.update(true, 1.0F), 0.0F, "shown inside 64 blocks");

        boolean at66 = DisplayCulling.isVisible(66.0D * 66.0D, 64.0D, 8.0D);
        assertTrue(at66, "66 blocks is still inside the hysteresis margin");
        assertTrue(Float.isNaN(state.update(at66, 1.0F)), "so no packet is sent");

        boolean at73 = DisplayCulling.isVisible(73.0D * 73.0D, 64.0D, 8.0D);
        assertFalse(at73, "73 blocks is outside the margin");
        assertEquals(0.0F, state.update(at73, 1.0F), 0.0F, "culled once");

        assertEquals(1.0F, state.update(at66, 1.0F), 0.0F, "and shown again when the player returns");
        assertTrue(Float.isNaN(state.update(at66, 1.0F)), "staying there sends nothing");
    }

    /** A cancelled task (scheduler shutdown, disable/enable) has to be rebuilt, or culling stops silently. */
    @Test
    void aCancelledTaskIsRebuiltButALiveOneIsLeftAlone() {
        assertTrue(DisplayCulling.needsFreshTask(null), "no task yet");
        assertTrue(DisplayCulling.needsFreshTask(PluginTask.NOOP), "a cancelled task must be rebuilt");
        assertFalse(DisplayCulling.needsFreshTask(LIVE_TASK), "a running task is left alone");
    }

    private static final PluginTask LIVE_TASK = new PluginTask() {

        @Override
        public void cancel() {
        }

        @Override
        public boolean isCancelled() {
            return false;
        }
    };
}
