package com.huidu.farmersdelight.visual;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A pass must flush exactly once: after the pass released itself and every announced task reported. */
class DisplaySyncBatchTest {

    @Test
    void theBatchWaitsForBothThePassAndItsTasks() {
        DisplaySyncBatch batch = new DisplaySyncBatch();
        batch.expect();
        batch.expect();
        assertFalse(batch.done(), "one task of two finished");
        assertFalse(batch.done(), "both tasks finished, but the pass is still handing out work");
        assertTrue(batch.release(), "releasing the pass completes the batch");
        assertTrue(batch.flushed());
    }

    @Test
    void aPassThatHandedOutNothingFlushesOnRelease() {
        DisplaySyncBatch batch = new DisplaySyncBatch();
        assertTrue(batch.release());
        assertTrue(batch.flushed());
        assertEquals(0, batch.outstandingTasks());
    }

    @Test
    void aRefusedTaskReportsLikeARunOne() {
        DisplaySyncBatch batch = new DisplaySyncBatch();
        batch.expect();
        // The scheduler refused it: the task never runs, so this report is the only one it makes.
        assertFalse(batch.done(), "the pass still has to release");
        assertTrue(batch.release(), "releasing after the refused task reports completes the batch");
    }

    @Test
    void anExtraReportCannotFlushTwice() {
        DisplaySyncBatch batch = new DisplaySyncBatch();
        batch.expect();
        assertFalse(batch.done());
        assertTrue(batch.release());
        assertFalse(batch.done(), "a late report must not start a second flush");
        assertFalse(batch.release());
    }

    @Test
    void aTaskReportingBeforeThePassReleasedDoesNotCompleteIt() {
        DisplaySyncBatch batch = new DisplaySyncBatch();
        batch.expect();
        // The unit finishes while the pass is still scheduling further displays.
        assertFalse(batch.done());
        assertFalse(batch.flushed(), "a task cannot stand in for the pass");
        batch.expect();
        assertFalse(batch.release(), "the second task is still outstanding, so releasing does not complete it");
        assertFalse(batch.flushed());
        assertTrue(batch.done(), "the last task reports after the pass released");
    }
}
