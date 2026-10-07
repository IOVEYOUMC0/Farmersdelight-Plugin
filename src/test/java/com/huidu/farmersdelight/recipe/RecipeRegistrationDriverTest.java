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
 * thread, a replaced round is cancelled, and a failure drops the rounds.
 *
 * Written against the real driver (not a stand-in), so removing the sharding — registering everything in one
 * go — turns these red. One reload rebuilds several owners back to back (the cooking pot and the cutting
 * board, each with its own file and pack sections), so the tests also pin that those rounds stay separate and
 * that a tail runs only after the last entry of its own round.
 */
class RecipeRegistrationDriverTest {

    private static final int BUDGET = 3;
    // Stands in for a recipe manager: the driver keeps one round per owner, matched by identity.
    private static final Object BOARD = new Object();
    private static final Object POT = new Object();

    @Test
    void everySliceStaysInsideTheBudget() {
        FakeArm arm = new FakeArm();
        RecipeRegistrationDriver driver = new RecipeRegistrationDriver(arm);
        List<String> registered = new ArrayList<>();
        driver.start(BOARD, single(ids(10), registered::add), BUDGET, () -> {
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
        driver.start(BOARD, single(ids(10), registered::add), BUDGET, () -> done.add("done"));

        while (driver.isRunning()) {
            arm.runTick();
        }

        assertEquals(ids(10), registered, "in order, once each");
        assertEquals(List.of("done"), done, "the tail runs exactly once");
        assertEquals("", driver.progress(), "no round left to report");
    }

    @Test
    void aFileInsideTheBudgetRunsInlineWithoutATick() {
        FakeArm arm = new FakeArm();
        RecipeRegistrationDriver driver = new RecipeRegistrationDriver(arm);
        List<String> registered = new ArrayList<>();
        driver.start(BOARD, single(ids(2), registered::add), BUDGET, () -> {
        });

        assertEquals(ids(2), registered, "small files keep the old synchronous behaviour");
        assertEquals(0, arm.arms, "and never arm a task");
        assertFalse(driver.isRunning());
    }

    // A reload of the same owner arriving mid-round replaces it: the old round registers nothing more and the
    // armed task keeps draining, now for the new round.
    @Test
    void startingAgainCancelsTheRoundThatWasRunning() {
        FakeArm arm = new FakeArm();
        RecipeRegistrationDriver driver = new RecipeRegistrationDriver(arm);
        List<String> first = new ArrayList<>();
        List<String> second = new ArrayList<>();
        driver.start(BOARD, single(ids(10), first::add), BUDGET, () -> {
        });
        assertEquals(BUDGET, first.size());

        driver.start(BOARD, single(ids(4), second::add), BUDGET, () -> {
        });

        assertEquals(BUDGET, first.size(), "the replaced round registers nothing more");
        assertEquals(ids(4).subList(0, BUDGET), second, "the new round starts from its first entry");
        arm.runTick();
        assertEquals(ids(4), second, "and finishes on the following tick");
        assertEquals(1, arm.arms, "the armed task is reused instead of armed again");
        assertFalse(driver.isRunning());
    }

    @Test
    void cancelStopsThePassAndItsTask() {
        FakeArm arm = new FakeArm();
        RecipeRegistrationDriver driver = new RecipeRegistrationDriver(arm);
        List<String> registered = new ArrayList<>();
        driver.start(BOARD, single(ids(10), registered::add), BUDGET, () -> {
        });

        driver.cancel();
        int frozen = registered.size();
        arm.runTick();
        arm.runTick();

        assertFalse(driver.isRunning());
        assertEquals(frozen, registered.size(), "a cancelled round registers nothing");
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
                () -> driver.start(BOARD, single(ids(10), step), BUDGET, () -> {
                }), "the failure has to reach the caller");

        assertFalse(driver.isRunning(), "and the half-finished round is gone");
        assertEquals(2, registered.size());
        assertEquals(0, arm.arms, "no follow-up tick was armed after the failure");
    }

    // A reload may have several sources (the plugin's own file plus one per CraftEngine pack section). They are
    // one round, so the tail — which the manager uses to publish — may only run once every source has
    // registered its last entry. Dropping the remainder of an earlier source turns this red.
    @Test
    void everySourceOfAReloadRoundIsRegisteredBeforeTheTail() {
        FakeArm arm = new FakeArm();
        RecipeRegistrationDriver driver = new RecipeRegistrationDriver(arm);
        List<String> registered = new ArrayList<>();
        List<Integer> published = new ArrayList<>();

        driver.start(BOARD, List.of(
                new RecipeRegistrationRound.Segment(ids(10), registered::add, null),
                new RecipeRegistrationRound.Segment(ids(10), registered::add, null)),
                BUDGET, () -> published.add(registered.size()));

        while (driver.isRunning()) {
            assertTrue(published.isEmpty(), "the tail must not run while a source still has entries");
            arm.runTick();
        }

        assertEquals(20, registered.size(), "every source is registered completely");
        assertEquals(List.of(20), published, "the tail runs once, after the last entry of the last source");
    }

    // Two owners are rebuilt by one reload, back to back. Neither may cancel the other's remaining entries, and
    // each publishes its own set from its own tail.
    @Test
    void twoOwnersKeepTheirOwnRounds() {
        FakeArm arm = new FakeArm();
        RecipeRegistrationDriver driver = new RecipeRegistrationDriver(arm);
        List<String> board = new ArrayList<>();
        List<String> pot = new ArrayList<>();
        List<String> published = new ArrayList<>();

        driver.start(BOARD, single(ids(10), board::add), BUDGET, () -> published.add("board"));
        driver.start(POT, single(ids(10), pot::add), BUDGET, () -> published.add("pot"));

        while (driver.isRunning()) {
            arm.runTick();
        }

        assertEquals(ids(10), board, "the first owner's round is not cancelled by the second owner");
        assertEquals(ids(10), pot, "each owner registers its own round completely");
        assertEquals(List.of("board", "pot"), published, "each tail runs once, after its own last entry");
        assertEquals(1, arm.arms, "one armed task drains every owner's round");
    }

    // The old behaviour for a file that fits the budget: no tick of delay, no armed task.
    @Test
    void aRoundInsideTheBudgetRunsInlineWithoutATick() {
        FakeArm arm = new FakeArm();
        RecipeRegistrationDriver driver = new RecipeRegistrationDriver(arm);
        List<String> registered = new ArrayList<>();
        List<String> tails = new ArrayList<>();

        driver.start(BOARD, List.of(
                new RecipeRegistrationRound.Segment(ids(2), registered::add, () -> tails.add("first")),
                new RecipeRegistrationRound.Segment(ids(2), registered::add, () -> tails.add("second"))),
                5, () -> tails.add("round"));

        assertEquals(4, registered.size(), "both sources run inline");
        assertEquals(List.of("first", "second", "round"), tails, "each source reports, then the round");
        assertEquals(0, arm.arms, "and never arm a task");
        assertFalse(driver.isRunning());
    }

    // The reload report reads i/N from progress(): N covers every running round, so the figure does not shrink
    // to one owner when a second one is rebuilt in the same pass.
    @Test
    void progressCountsEveryRunningRound() {
        FakeArm arm = new FakeArm();
        RecipeRegistrationDriver driver = new RecipeRegistrationDriver(arm);
        driver.start(BOARD, single(ids(10), id -> {
        }), BUDGET, () -> {
        });
        driver.start(POT, single(ids(10), id -> {
        }), BUDGET, () -> {
        });

        assertEquals("6/20", driver.progress(), "i/N counts every running round");
        arm.runTick();
        assertEquals("12/20", driver.progress(), "and grows with every registered entry");
    }

    // The tail is where a manager builds its indexes and publishes. On Folia the tail runs on the global
    // region thread while readers run on region threads, so the set has to be final the moment the tail runs:
    // no later tick may register another entry into the round's buffer.
    @Test
    void nothingIsRegisteredAfterTheTailRan() {
        FakeArm arm = new FakeArm();
        RecipeRegistrationDriver driver = new RecipeRegistrationDriver(arm);
        List<String> buffer = new ArrayList<>();
        List<Integer> published = new ArrayList<>();

        driver.start(BOARD, single(ids(10), buffer::add), BUDGET, () -> published.add(buffer.size()));
        while (driver.isRunning()) {
            arm.runTick();
        }

        assertEquals(List.of(10), published, "the tail saw every entry of the round");
        arm.runTick();
        arm.runTick();

        assertEquals(ids(10), buffer, "a retired round registers nothing after its tail ran");
        assertEquals(List.of(10), published, "and the tail does not run again");
        assertEquals("", driver.progress());
        assertFalse(driver.isRunning());
    }

    private static List<RecipeRegistrationRound.Segment> single(List<String> ids, RecipeRegistrationBatch.Step step) {
        return List.of(new RecipeRegistrationRound.Segment(ids, step, null));
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
