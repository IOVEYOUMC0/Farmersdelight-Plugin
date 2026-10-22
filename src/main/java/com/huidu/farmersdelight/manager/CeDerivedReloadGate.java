package com.huidu.farmersdelight.manager;

/**
 * Decides whether the CraftEngine-derived reload work has to run again.
 */
public final class CeDerivedReloadGate {

    private long currentRevision;
    private long appliedRevision = -1L;

    /** Called once per CraftEngine reload: only a new revision re-arms the derived work. */
    public synchronized void bumpRevision() {
        currentRevision++;
    }

    /** True when the derived work has not been applied for the current revision yet. */
    public synchronized boolean needsRefresh() {
        return appliedRevision != currentRevision;
    }

    /** Marks the current revision as applied. */
    public synchronized void markApplied() {
        appliedRevision = currentRevision;
    }

    /** Test and re-enable hook. */
    public synchronized void reset() {
        currentRevision = 0L;
        appliedRevision = -1L;
    }
}
