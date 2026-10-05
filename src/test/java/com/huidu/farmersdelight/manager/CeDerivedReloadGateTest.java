package com.huidu.farmersdelight.manager;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CE-derived reload gate: work that only CraftEngine can invalidate must not run for the plugin's own reloads.
 */
class CeDerivedReloadGateTest {

    @Test
    void theFirstPassRunsAndTheNextOneWithNoCraftEngineReloadDoesNot() {
        CeDerivedReloadGate gate = new CeDerivedReloadGate();

        assertTrue(gate.needsRefresh(), "the first pass has to build the CE-derived state");
        gate.markApplied();
        assertFalse(gate.needsRefresh(), "an ordinary /fd reload recipes must not rescan campfire recipes");
    }

    @Test
    void aCraftEngineReloadReArmsTheWork() {
        CeDerivedReloadGate gate = new CeDerivedReloadGate();
        gate.markApplied();
        assertFalse(gate.needsRefresh());

        gate.bumpRevision();

        assertTrue(gate.needsRefresh(), "a CraftEngine reload is exactly what makes this work stale");
        gate.markApplied();
        assertFalse(gate.needsRefresh());
    }

    @Test
    void resetStartsOver() {
        CeDerivedReloadGate gate = new CeDerivedReloadGate();
        gate.markApplied();
        gate.reset();
        assertTrue(gate.needsRefresh());
    }
}
