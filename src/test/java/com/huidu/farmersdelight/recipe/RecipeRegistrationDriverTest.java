package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.util.scheduler.PluginTask;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sharded reload path itself: the first slice runs inline, the rest is budgeted per tick on the calling
 * thread, a replaced pass is cancelled, and a failure drops the batch.
 *
 *
 * Written against the real driver (not a stand-in), so removing the sharding — registering everything in one
 * go — turns these red.
 */
class RecipeRegistrationDriverTest {

    private static final int BUDGET = 3;

    @Test
    void everySliceStaysInsideTheBudget() {
        FakeArm arm = new FakeArm();
        RecipeRegistrationDriver driver = new RecipeRegistrationDriver(arm);
        List<String> registered = new ArrayList<>();
        driver.start(ids(10), registered::add, BUDGET, () -> {
        });

        assertEquals(BUDGET, registered.size(), "the inline slice is one budget, not the whole file");
        for (int tick = 0; tick < 3; tick++) {
            int before = registered.size();
            arm.runTick();
            assertTrue(registered.size() - before <= BUDGET,
                    "tick " + tick + " registered more than the budget");
        }
    }

    @Test
    void theWholeFileIsRegisteredExactlyOnce() {
        FakeArm arm = new FakeArm();
        RecipeRegistrationDriver driver = new RecipeRegistrationDriver(arm);
        List<String> registered = new ArrayList<>();
        List<String> done = new ArrayList<>();
        driver.start(ids(10), registered::add, BUDGET, () -> done.add("done"));

        while (driver.isRunning()) {
            arm.runTick();
        }

        assertEquals(ids(10), registered, "in order, once each");
        assertEquals(List.of("done"), done, "the tail runs exactly once");
        assertEquals("", driver.progress(), "no pass left to report");
    }

    @Test
    void aFileInsideTheBudgetRunsInlineWithoutATick() {
        FakeArm arm = new FakeArm();
        RecipeRegistrationDriver driver = new RecipeRegistrationDriver(arm);
        List<String> registered = new ArrayList<>();
        driver.start(ids(2), registered::add, BUDGET, () -> {
        });

        assertEquals(ids(2), registered, "small files keep the old synchronous behaviour");
        assertEquals(0, arm.arms, "and never arm a task");
        assertFalse(driver.isRunning());
    }

    @Test
    void startingAgainCancelsThePassThatWasRunning() {
        FakeArm arm = new FakeArm();
        RecipeRegistrationDriver driver = new RecipeRegistrationDriver(arm);
        List<String> first = new ArrayList<>();
        List<String> second = new ArrayList<>();
        driver.start(ids(10), first::add, BUDGET, () -> {
        });
        assertEquals(BUDGET, first.size());

        driver.start(ids(4), second::add, BUDGET, () -> {
        });

        assertEquals(BUDGET, first.size(), "the replaced pass registers nothing more");
        assertEquals(ids(4).subList(0, BUDGET), second, "the new pass starts from its first entry");
        arm.runTick();
        assertEquals(ids(4), second, "and finishes on the following tick");
        assertTrue(arm.firstTaskCancelled(), "and the replaced pass's task was cancelled");
    }

    @Test
    void cancelStopsThePassAndItsTask() {
        FakeArm arm = new FakeArm();
        RecipeRegistrationDriver driver = new RecipeRegistrationDriver(arm);
        List<String> registered = new ArrayList<>();
        driver.start(ids(10), registered::add, BUDGET, () -> {
        });

        driver.cancel();
        int frozen = registered.size();
        arm.runTick();
        arm.runTick();

        assertFalse(driver.isRunning());
        assertEquals(frozen, registered.size(), "a cancelled pass registers nothing");
        assertEquals(1, arm.cancelledTasks());
    }

    @Test
    void aFailingStepDropsTheBatchLoudly() {
        FakeArm arm = new FakeArm();
        RecipeRegistrationDriver driver = new RecipeRegistrationDriver(arm);
        List<String> registered = new ArrayList<>();
        RecipeRegistrationBatch.Step step = id -> {
            if (registered.size() == 2) {
                throw new IllegalStateException("bad recipe entry");
            }
            registered.add(id);
        };

        assertThrows(IllegalStateException.class,
                () -> driver.start(ids(10), step, BUDGET, () -> {
                }), "the failure has to reach the caller");

        assertFalse(driver.isRunning(), "and the half-finished batch is gone");
        assertEquals(2, registered.size());
        assertEquals(0, arm.arms, "no follow-up tick was armed after the failure");
    }

    private static List<String> ids(int size) {
        return IntStream.range(0, size).mapToObj(i -> "recipe_" + i).toList();
    }

    /** Records what was armed and runs it on demand; no scheduler involved. */
    private static final class FakeArm implements RecipeRegistrationDriver.TaskArm {

        private final List<FakeTask> tasks = new ArrayList<>();
        private Runnable armed;
        private int arms;

        @Override
        public PluginTask arm(Runnable tick) {
            this.armed = tick;
            this.arms++;
            FakeTask task = new FakeTask();
            this.tasks.add(task);
            return task;
        }

        private void runTick() {
            if (this.armed != null) {
                this.armed.run();
            }
        }

        private long cancelledTasks() {
            return this.tasks.stream().filter(task -> task.cancelled).count();
        }

        private boolean firstTaskCancelled() {
            return !this.tasks.isEmpty() && this.tasks.get(0).cancelled;
        }
    }

    private static final class FakeTask implements PluginTask {

        private boolean cancelled;

        @Override
        public void cancel() {
            this.cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return this.cancelled;
        }
    }
}
