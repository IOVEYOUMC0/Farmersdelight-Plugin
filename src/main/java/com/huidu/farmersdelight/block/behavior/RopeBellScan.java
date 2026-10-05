package com.huidu.farmersdelight.block.behavior;

import java.util.function.IntPredicate;

/**
 * Where the bell above a rope column is, if there is one.
 *
 *
 * Upstream parity: the search walks up through a contiguous rope column and rings the first bell it finds; a
 * gap, or any block that is neither a rope nor a bell, ends the search. Pure so the rule is provable without a
 * server, and shared by both empty-hand entry points — CraftEngine decides for itself whether the
 * useWithoutItem dispatch happens, and a bell that never rings is exactly the reported defect.
 */
public final class RopeBellScan {

    private RopeBellScan() {
    }

    /**
     * Offset above the clicked rope (0 = the block directly above) of the first bell, or -1 when the column
     * does not lead to one. isRope/isBell are asked about each offset in order, so a gap or a
     * foreign block stops the walk exactly like the world scan it replaces.
     */
    public static int findBellOffset(int maxDistance, IntPredicate isRope, IntPredicate isBell) {
        if (isRope == null || isBell == null) {
            return -1;
        }
        for (int offset = 0; offset < Math.max(0, maxDistance); offset++) {
            if (isBell.test(offset)) {
                return offset;
            }
            if (!isRope.test(offset)) {
                return -1;
            }
        }
        return -1;
    }
}
