package com.huidu.farmersdelight.visual;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    void hysteresisKeepsaViewerAtTheBoundaryFromFlapping() {
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

    @Test
    void cullingNeverProducesARemovalSignal() {
        DisplayCulling.ViewRangeState state = new DisplayCulling.ViewRangeState();
        assertEquals(1.0F, state.update(true, 1.0F), 0.0F);
        assertEquals(0.0F, state.update(false, 1.0F), 0.0F, "the only signal a cull produces is range 0");
        assertNotEquals(Float.NaN, state.update(true, 1.0F), "the entity is still there to bring back");
        assertEquals(1.0F, state.lastSent(), 0.0F);
    }
}
