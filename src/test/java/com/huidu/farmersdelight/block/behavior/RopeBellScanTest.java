package com.huidu.farmersdelight.block.behavior;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The bell-above-a-rope rule, and the reason it now runs from both empty-hand entry points: the reported defect
 * was a bell at the top of a rope that simply never rang, so the scan has to stay provably correct and cheap.
 */
class RopeBellScanTest {

    private static final int MAX = 24;

    /** Column described by characters: 'r' rope, 'b' bell, 'a' air/gap, 'x' any other block. */
    private static int scan(String column) {
        List<Character> blocks = column.chars().mapToObj(c -> (char) c).toList();
        IntPredicate isRope = offset -> offset < blocks.size() && blocks.get(offset) == 'r';
        IntPredicate isBell = offset -> offset < blocks.size() && blocks.get(offset) == 'b';
        return RopeBellScan.findBellOffset(MAX, isRope, isBell);
    }

    @Test
    void aBellDirectlyAboveTheRopeIsFound() {
        assertEquals(0, scan("b"));
    }

    @Test
    void theBellAtTheTopOfAContiguousColumnIsFound() {
        assertEquals(3, scan("rrrb"), "three ropes then the bell");
        assertEquals(1, scan("rb"));
    }

    @Test
    void aGapEndsTheSearch() {
        assertEquals(-1, scan("rrab"), "air between rope and bell stops the walk");
        assertEquals(-1, scan("arb"));
    }

    @Test
    void anyOtherBlockEndsTheSearch() {
        assertEquals(-1, scan("rxb"), "a non-rope block is not walked through");
    }

    @Test
    void theDistanceLimitIsRespected() {
        assertEquals(-1, RopeBellScan.findBellOffset(2, offset -> true, offset -> offset == 2),
                "a bell beyond the configured distance is out of reach");
        assertEquals(2, RopeBellScan.findBellOffset(3, offset -> true, offset -> offset == 2));
    }

    @Test
    void noColumnMeansNoBell() {
        assertEquals(-1, scan(""));
        assertEquals(-1, scan("rrr"), "ropes with no bell on top");
        assertEquals(-1, RopeBellScan.findBellOffset(0, offset -> true, offset -> true));
        assertEquals(-1, RopeBellScan.findBellOffset(MAX, null, offset -> true));
        assertEquals(-1, RopeBellScan.findBellOffset(MAX, offset -> true, null));
    }
}
