package com.huidu.farmersdelight.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The cooking pot's take decision, now that it is the shared arithmetic. The expected values come from a copy
 * of the branches this class used to run, so the table says the shared rule answers every click shape the way
 * the pot itself answered it — and the differences the pot is allowed to keep are asserted one by one.
 */
class CookingPotOutputTakerTest {

    @Test
    void everyClickShapeResolvesToTheAmountTheOldBranchesReturned() {
        int rows = 0;
        for (boolean shift : new boolean[] {false, true}) {
            for (boolean right : new boolean[] {false, true}) {
                for (int stored : new int[] {0, 1, 5, 16, 64}) {
                    for (int cursorAmount : new int[] {0, 1, 4, 15, 16, 20, 64}) {
                        for (boolean cursorEmpty : new boolean[] {false, true}) {
                            for (boolean similar : new boolean[] {false, true}) {
                                for (int itemMax : new int[] {1, 16, 64}) {
                                    for (int containerMax : new int[] {1, 16, 64}) {
                                        int expected = legacyResolve(shift, right, stored, cursorAmount,
                                                cursorEmpty, similar, itemMax, containerMax);
                                        assertEquals(expected, CookingPotOutputTaker.resolveTake(shift, right, stored,
                                                        cursorAmount, cursorEmpty, similar, itemMax, containerMax),
                                                "shift=" + shift + " right=" + right + " stored=" + stored
                                                        + " cursor=" + cursorAmount + " empty=" + cursorEmpty
                                                        + " similar=" + similar + " itemMax=" + itemMax
                                                        + " containerMax=" + containerMax);
                                        rows++;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        assertEquals(5040, rows, "every combination of the table ran");
    }

    @Test
    void theDifferencesThePotIsAllowedToKeepAreStillThere() {
        // A shift click hands the whole stack over even when the cursor holds something else.
        assertEquals(64, CookingPotOutputTaker.resolveTake(true, false, 64, 5, false, false, 64, 64));
        // The container's own maximum clamps the take; this is the pot's difference from a plain left click.
        assertEquals(16, CookingPotOutputTaker.resolveTake(false, false, 64, 0, true, false, 64, 16));
        // A foreign cursor takes nothing.
        assertEquals(0, CookingPotOutputTaker.resolveTake(false, false, 64, 5, false, false, 64, 64));
        // A right click takes exactly one.
        assertEquals(1, CookingPotOutputTaker.resolveTake(false, true, 64, 0, true, false, 64, 64));
        // An empty slot takes nothing whatever the click was.
        assertEquals(0, CookingPotOutputTaker.resolveTake(true, true, 0, 0, true, false, 64, 64));
    }

    @Test
    void aLimitBelowOneIsUnreachableAndTakesNothing() {
        // Inventory.getMaxStackSize() is at least one, so a live view cannot produce a limit below one. If one
        // ever appeared, the branches this class used to run took one on a right click while the shared rule
        // takes nothing: the shared rule is the one the pot follows now, and nothing can reach this row.
        assertEquals(1, legacyResolve(false, true, 5, 0, true, false, 16, 0));
        assertEquals(0, CookingPotOutputTaker.resolveTake(false, true, 5, 0, true, false, 16, 0));
    }

    /**
     * The branches this class ran before it delegated: shift takes the stack, an empty cursor takes one on a
     * right click and the clamped stack otherwise, a foreign cursor takes nothing, and a similar cursor takes
     * what is left of the limit. Similar stacks share one maximum stack size, so the cursor's own maximum the
     * old code read is the same number as the stored item's.
     */
    private static int legacyResolve(boolean shift, boolean right, int stored, int cursorAmount,
                                     boolean cursorEmpty, boolean similar, int itemMax, int containerMax) {
        if (stored <= 0) {
            return 0;
        }
        if (shift) {
            return stored;
        }
        int limit = Math.min(itemMax, containerMax);
        if (cursorEmpty) {
            return right ? 1 : Math.min(stored, limit);
        }
        if (!similar) {
            return 0;
        }
        int space = limit - cursorAmount;
        if (space <= 0) {
            return 0;
        }
        return Math.min(right ? 1 : stored, space);
    }
}
