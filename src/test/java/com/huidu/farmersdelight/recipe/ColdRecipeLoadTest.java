package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.util.scheduler.PluginTask;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a manager has published by the time its load returns, for the two budgets it chooses between.
 *
 * The CraftEngine readiness pass runs the recipe-backed warmup and prints the content summary immediately
 * after the managers are rebuilt, so a load with nothing published yet has to be complete when it returns.
 * A load that already has a published set keeps the configured budget and finishes over the next ticks.
 * Reverting the first-load budget to the configured one is what leaves the startup summary and the warmup
 * reading an empty recipe set.
 */
class ColdRecipeLoadTest {

    private static final int CONFIGURED = 32;
    private static final int ENTRIES = 112;

    @Test
    void aFirstLoadPublishesEveryRecipeBeforeItReturns() {
        FakeArm arm = new FakeArm();
        RecipeRegistrationDriver driver = new RecipeRegistrationDriver(arm);
        List<String> buffer = new ArrayList<>();
        List<Integer> published = new ArrayList<>();

        driver.start(new Object(), List.of(segment(ENTRIES, buffer)),
                RecipeRegistrationBudget.forLoad(0, CONFIGURED), () -> published.add(buffer.size()));

        assertEquals(ENTRIES, buffer.size(), "a first load registers the whole file inline");
        assertEquals(List.of(ENTRIES), published, "and publishes it before the load returns");
        assertEquals(0, arm.arms, "so nothing is left to arm");
        assertFalse(driver.isRunning());
    }

    @Test
    void aLoadWithAPublishedSetKeepsSlicingAcrossTicks() {
        FakeArm arm = new FakeArm();
        RecipeRegistrationDriver driver = new RecipeRegistrationDriver(arm);
        List<String> buffer = new ArrayList<>();
        List<Integer> published = new ArrayList<>();

        driver.start(new Object(), List.of(segment(ENTRIES, buffer)),
                RecipeRegistrationBudget.forLoad(ENTRIES, CONFIGURED), () -> published.add(buffer.size()));

        assertEquals(CONFIGURED, buffer.size(), "a load that has a published set pays one slice up front");
        assertTrue(published.isEmpty(), "and publishes nothing until the round is complete");
        assertEquals(1, arm.arms, "the rest continues on the following ticks");

        while (driver.isRunning()) {
            arm.runTick();
        }

        assertEquals(ENTRIES, buffer.size());
        assertEquals(List.of(ENTRIES), published);
    }

    /** The manager's side of a load: one source holding every entry, plus what the publish saw. */
    private static RecipeRegistrationRound.Segment segment(int entries, List<String> buffer) {
        return new RecipeRegistrationRound.Segment(
                IntStream.range(0, entries).mapToObj(i -> "recipe_" + i).toList(), buffer::add, null);
    }

    /** Records what was armed and runs it on demand; no scheduler involved. */
    private static final class FakeArm implements RecipeRegistrationDriver.TaskArm {

        private Runnable armed;
        private int arms;

        @Override
        public PluginTask arm(Runnable tick) {
            this.armed = tick;
            this.arms++;
            return new PluginTask() {
                private boolean cancelled;

                @Override
                public void cancel() {
                    this.cancelled = true;
                }

                @Override
                public boolean isCancelled() {
                    return this.cancelled;
                }
            };
        }

        private void runTick() {
            if (this.armed != null) {
                this.armed.run();
            }
        }
    }
}
