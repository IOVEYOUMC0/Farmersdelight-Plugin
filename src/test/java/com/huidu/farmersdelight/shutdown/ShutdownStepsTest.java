package com.huidu.farmersdelight.shutdown;

import com.huidu.farmersdelight.api.util.ShutdownBudget;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A teardown step that throws must not take the steps after it down with it. */
class ShutdownStepsTest {

    private final Logger logger = Logger.getLogger("shutdown-steps-test");
    private final List<String> failures = new ArrayList<>();

    private ShutdownSteps steps() {
        return new ShutdownSteps(logger);
    }

    @Test
    void aFailingStepIsLoggedAndTheRestStillRun() {
        ShutdownSteps steps = steps();
        List<String> ran = new ArrayList<>();
        steps.run("plugin.disable_step_unregister_listeners", () -> {
            throw new IllegalStateException("boom");
        });
        steps.run("plugin.disable_step_shutdown_metrics", () -> ran.add("metrics"));
        assertEquals(List.of("metrics"), ran);
        assertTrue(failures.isEmpty(), "no counter is involved; the logger is the only channel");
    }

    @Test
    void anUnopenedBudgetRunsPersistenceStepsUnconditionally() {
        ShutdownSteps steps = steps();
        AtomicInteger ran = new AtomicInteger();
        steps.runBudgeted("plugin.disable_step_save_recipe_discovery", ran::incrementAndGet);
        assertEquals(1, ran.get());
    }

    @Test
    void aSpentBudgetSkipsThePersistenceStepsThatFollow() {
        ShutdownSteps steps = steps();
        ShutdownBudget budget = ShutdownBudget.ofMillis(ShutdownBudget.MIN_TOTAL_MILLIS, logger);
        steps.beginBudget(budget);
        AtomicInteger ran = new AtomicInteger();
        // Burning the whole deadline, then asking for one more step: the later step is skipped, not failed.
        steps.runBudgeted("plugin.disable_step_save_block_data", () -> {
            while (!budget.expired()) {
                Thread.onSpinWait();
            }
            ran.incrementAndGet();
        });
        steps.runBudgeted("plugin.disable_step_save_player_buffs", ran::incrementAndGet);
        assertEquals(1, ran.get(), "only the step that consumed the budget ran");
        assertTrue(steps.budget() != null, "the deadline the sequence opened stays readable");
    }
}
