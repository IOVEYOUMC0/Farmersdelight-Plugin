package com.huidu.farmersdelight.debug;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DebugUndoGuardTest {
    @Test
    void replacementWithTheSameIdAndDataIsPreserved() {
        var original = new DebugUndoGuard.State("fd:pot", "stone", new Object());
        var replacement = new DebugUndoGuard.State("fd:pot", "stone", new Object());
        assertFalse(DebugUndoGuard.stillOwned(original, replacement));
    }

    @Test
    void tickingTheOriginalControllerDoesNotStrandTheCleanup() {
        Object controller = new Object();
        assertTrue(DebugUndoGuard.stillOwned(
                new DebugUndoGuard.State("fd:pot", "unlit", controller),
                new DebugUndoGuard.State("fd:pot", "lit", controller)));
        assertFalse(DebugUndoGuard.stillOwned(
                new DebugUndoGuard.State("fd:pot", "unlit", controller),
                new DebugUndoGuard.State("fd:stove", "unlit", controller)));
    }

    @Test
    void plainBlocksRequireTheCapturedDataAndNoNewController() {
        var expected = new DebugUndoGuard.State(null, "stone", null);
        assertTrue(DebugUndoGuard.stillOwned(expected, new DebugUndoGuard.State(null, "stone", null)));
        assertFalse(DebugUndoGuard.stillOwned(expected, new DebugUndoGuard.State(null, "gold_block", null)));
        assertFalse(DebugUndoGuard.stillOwned(expected, new DebugUndoGuard.State(null, "stone", new Object())));
        assertFalse(DebugUndoGuard.stillOwned(null, expected));
    }
}
