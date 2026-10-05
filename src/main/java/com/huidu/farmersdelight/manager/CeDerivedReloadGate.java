package com.huidu.farmersdelight.manager;

/**
 * Decides whether the CraftEngine-derived reload work has to run again.
 *
 *
 * Three steps of a reload are derived from CraftEngine's registries, not from the plugin's own files: the pet-food scan
 * (a CE item setting), the stove recipe cache and the skillet recipe cache (both walk the server's campfire
 * recipes). They are needed after CraftEngine reloads its pack, but /fd reload recipes — the command the
 * operator runs most — does not touch CraftEngine at all, yet paid for all three every time. A revision counter,
 * bumped by the CraftEngineReloadEvent handler, lets that work be skipped when nothing CraftEngine-side
 * changed.
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
