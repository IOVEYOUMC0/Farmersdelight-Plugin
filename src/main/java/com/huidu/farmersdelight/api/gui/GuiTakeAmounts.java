package com.huidu.farmersdelight.api.gui;

/**
 * How many items a container click takes out of a slot, as plain arithmetic that needs no server.
 *
 * The container GUIs here solved this the same way but each in its own copy, and the copies have drifted: the
 * cooking pot clamps a take to the container's own stack size, the keg answers a drop key with the whole
 * stack, and the crab trap accepts only as much as fits on the cursor and in the container. This class holds
 * the arithmetic they share; the caller passes the click shape and the limit it accepts, so every container
 * keeps its own answer while the branches live in one place.
 *
 * A take of zero means the click must not be applied.
 */
public final class GuiTakeAmounts {

    /** The shape of the click that asked for the items, as plain values. */
    public enum Click {
        /** A left click or a number key: as much as fits. */
        LEFT,
        /** A right click: one item. */
        RIGHT,
        /** A shift click: the whole stack. */
        SHIFT,
        /** The drop key: one item. */
        DROP_ONE,
        /** Control plus the drop key: the whole stack. */
        DROP_ALL
    }

    private GuiTakeAmounts() {
    }

    /**
     * How many items a click takes from a slot holding stored items onto a cursor that is empty or holds the
     * same item. Limit is the most the caller accepts, normally the smaller of the item's stack size and the
     * container's; a limit below one takes nothing, and a click on an empty slot takes nothing. The result is
     * never negative and never more than the slot holds.
     */
    public static int take(Click click, int stored, int cursorAmount, boolean cursorEmpty, boolean cursorSimilar,
                           int limit) {
        if (stored <= 0) {
            return 0;
        }
        if (click == Click.SHIFT || click == Click.DROP_ALL) {
            return stored;
        }
        if (click == Click.DROP_ONE) {
            return 1;
        }
        int accepted = Math.max(0, limit);
        int wanted = click == Click.RIGHT ? 1 : stored;
        if (cursorEmpty) {
            return Math.min(wanted, Math.min(stored, accepted));
        }
        if (!cursorSimilar) {
            return 0;
        }
        int room = accepted - Math.max(0, cursorAmount);
        return room <= 0 ? 0 : Math.min(wanted, room);
    }
}
