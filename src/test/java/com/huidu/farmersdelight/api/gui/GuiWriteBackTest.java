package com.huidu.farmersdelight.api.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The write-back guard, as plain values: item stacks cannot be built without a running server, so the whole
 * truth table is asserted through the primitive overload. The first five cases are the ones the addon helper
 * carried before this class owned the rule.
 */
class GuiWriteBackTest {

    @Test
    void anUnchangedCellCommits() {
        assertTrue(GuiWriteBack.mayCommit(true, true, false, 0, 0));
        assertTrue(GuiWriteBack.mayCommit(false, false, true, 3, 3));
    }

    @Test
    void baitTheTickerSpentIsNotPutBackByAStaleSnapshot() {
        // Painted with eight bait; the ticker ate it and left the cell empty (or a spent chum bucket behind).
        assertFalse(GuiWriteBack.mayCommit(true, false, false, 0, 8),
                "the trap ate the bait; committing the snapshot would hand it back");
        assertFalse(GuiWriteBack.mayCommit(false, false, false, 1, 8),
                "a chum bucket leaves its bucket behind, which is not the painted stack either");
    }

    @Test
    void aCatchThatArrivedMeanwhileIsNotErased() {
        assertFalse(GuiWriteBack.mayCommit(false, true, false, 1, 0),
                "the cell was painted empty and the ticker filled it; the snapshot must not clear it again");
    }

    @Test
    void aPartialWithdrawalIsNotCommitted() {
        assertFalse(GuiWriteBack.mayCommit(false, false, true, 2, 5),
                "another viewer took three; committing the painted five would multiply them");
    }

    @Test
    void aDifferentStackDoesNotMatch() {
        assertFalse(GuiWriteBack.mayCommit(false, false, false, 1, 1));
    }

    @Test
    void anEmptyCellOnEitherSideNeedsBothSidesEmpty() {
        assertTrue(GuiWriteBack.mayCommit(true, true, false, 0, 0));
        assertTrue(GuiWriteBack.mayCommit(true, true, true, 0, 0),
                "similarity is not consulted once both sides are empty");
        assertFalse(GuiWriteBack.mayCommit(false, true, true, 4, 0));
        assertFalse(GuiWriteBack.mayCommit(true, false, true, 0, 4));
    }

    @Test
    void theSameStackInTheSameAmountCommitsAndAnyOtherAmountDoesNot() {
        assertTrue(GuiWriteBack.mayCommit(false, false, true, 1, 1));
        assertFalse(GuiWriteBack.mayCommit(false, false, true, 1, 2));
        assertFalse(GuiWriteBack.mayCommit(false, false, true, 64, 63));
    }

    @Test
    void theStackOverloadTreatsNullAsAnEmptyCell() {
        // The only case reachable without a server: both sides null, i.e. nothing painted and nothing stored.
        assertTrue(GuiWriteBack.mayCommit(null, null));
    }
}
