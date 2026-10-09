package com.huidu.farmersdelight.debug;

import java.util.Objects;

final class DebugUndoGuard {
    record State(String blockId, String blockData, Object controller) { }
    private DebugUndoGuard() { }

    static boolean stillOwned(State expected, State current) {
        if (expected == null || current == null || !Objects.equals(expected.blockId(), current.blockId())) return false;
        if (expected.controller() != null) return expected.controller() == current.controller();
        return current.controller() == null && Objects.equals(expected.blockData(), current.blockData());
    }
}
