package com.huidu.farmersdelight.util.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduledTaskAdapterTest {
    @Test
    void aRejectedEntityTaskProducesTheNoOpHandle() {
        assertSame(PluginTask.NOOP, SchedulerAdapter.wrap((ScheduledTask) null));
    }

    @Test
    void cancellationUsesTheScheduledTasksOwnState() {
        for (ScheduledTask.ExecutionState state : ScheduledTask.ExecutionState.values()) {
            TestTask task = new TestTask(state);
            assertEquals(state == ScheduledTask.ExecutionState.CANCELLED
                    || state == ScheduledTask.ExecutionState.CANCELLED_RUNNING,
                    SchedulerAdapter.wrap(task).isCancelled());
        }
        TestTask task = new TestTask(ScheduledTask.ExecutionState.IDLE);
        PluginTask handle = SchedulerAdapter.wrap(task);
        assertFalse(handle.isCancelled());
        handle.cancel();
        assertTrue(handle.isCancelled());
        assertEquals(1, task.cancellations);
    }

    private static final class TestTask implements ScheduledTask {
        private ExecutionState state;
        private int cancellations;

        TestTask(ExecutionState state) { this.state = state; }

        @Override public Plugin getOwningPlugin() { return null; }
        @Override public boolean isRepeatingTask() { return false; }
        @Override public ExecutionState getExecutionState() { return state; }
        @Override public CancelledState cancel() {
            cancellations++;
            state = ExecutionState.CANCELLED;
            return CancelledState.CANCELLED_BY_CALLER;
        }
    }
}
