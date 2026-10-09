package com.huidu.farmersdelight.block.behavior;

import java.util.function.IntPredicate;

/**
 * Finds the first bell above a contiguous rope column.
 * A gap or a block other than rope or bell ends the search. Both empty-hand entry points share this rule.
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
