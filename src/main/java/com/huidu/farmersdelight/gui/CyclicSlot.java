package com.huidu.farmersdelight.gui;

/**
 * Generic auto-cycle display for recipe detail previews (the tick loop only advances a
 * frame; rendering stays in the page that owns the slot). Drives "switch to the next display item every N
 * ticks" for any detail-page slot that needs to cycle through candidates — the cutting-board tool preview,
 * the special-recipe catalyst items, and so on. The index is taken modulo the current option count.
 *
 *
 * The interval counts the tick() calls the owner makes, which is once per GuiTickManager callback
 * (GuiTickManager.TICK_INTERVAL game ticks), not game ticks.
 */
final class CyclicSlot {

    private final int intervalCallbacks;
    private int ticks;
    private int index;

    CyclicSlot(int intervalCallbacks) {
        this.intervalCallbacks = Math.max(1, intervalCallbacks);
    }

    /** Back to the first display item. */
    void reset() {
        ticks = 0;
        index = 0;
    }

    /** Call once per tick; returns true on the tick the internal index actually advances. */
    boolean tick() {
        ticks++;
        if (ticks < intervalCallbacks) {
            return false;
        }
        ticks = 0;
        index++;
        return true;
    }

    /** Current display index, taken modulo optionCount (0 when optionCount <= 0). */
    int current(int optionCount) {
        return optionCount <= 0 ? 0 : index % optionCount;
    }
}
