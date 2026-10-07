package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.util.scheduler.PluginTask;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One readiness pass's view of its own publication: the work that waits on it runs only after every round the
 * pass started has published, and it always runs exactly once. A pass that failed, or that a newer pass
 * replaced, reports that instead of leaving the caller waiting for a round that will never publish.
 */
class RecipePublicationWatchTest {

    private static final int BUDGET = 32;

    @Test
    void aPassThatFitsTheBudgetContinuesInTheSameTick() {
        FakeArm arm = new FakeArm();
        RecipeRegistrationDriver driver = new RecipeRegistrationDriver(arm);
        FakeManager pot = new FakeManager();

        RecipePublicationWatch watch = driver.beginPublicationWatch();
        List<String> seen = new ArrayList<>();
        watch.whenPublished(outcome -> seen.add(outcome.published() + ":" + pot.published + ":" + pot.entries.size()));
        driver.start(new Object(), List.of(pot.segment(20)), BUDGET, pot::publish);
        driver.endPublicationWatch();

        assertEquals(List.of("true:true:20"), seen,
                "a load whose files fit the budget publishes during the call, so the work runs in the same tick");
        assertFalse(driver.isRunning());
        assertEquals(0, arm.arms, "nothing is left for a later tick");
    }

    @Test
    void aPassWaitsForEveryRoundItStarted() {
        FakeArm arm = new FakeArm();
        RecipeRegistrationDriver driver = new RecipeRegistrationDriver(arm);
        FakeManager pot = new FakeManager();
        FakeManager board = new FakeManager();

        RecipePublicationWatch watch = driver.beginPublicationWatch();
        List<String> seen = new ArrayList<>();
        watch.whenPublished(outcome -> seen.add(
                outcome.published() + ":" + pot.published + ":" + pot.entries.size()
                        + ":" + board.published + ":" + board.entries.size()));
        driver.start(new Object(), List.of(pot.segment(112)), BUDGET, pot::publish);
        driver.start(new Object(), List.of(board.segment(20)), BUDGET, board::publish);
        driver.endPublicationWatch();

        assertTrue(seen.isEmpty(), "the pass must not report while one of its rounds is still registering");
        assertTrue(arm.arms > 0, "the sharded round continues on a later tick");
        while (driver.isRunning()) {
            arm.runTick();
        }
        assertEquals(List.of("true:true:112:true:20"), seen);
    }

    @Test
    void aFailedRoundReportsTheFailureInsteadOfWaiting() {
        FakeArm arm = new FakeArm();
        RecipeRegistrationDriver driver = new RecipeRegistrationDriver(arm);
        FakeManager pot = new FakeManager();
        RecipeRegistrationRound.Segment failing = new RecipeRegistrationRound.Segment(
                List.of("a", "b", "c"), id -> {
            if ("c".equals(id)) {
                throw new IllegalStateException("registration failed");
            }
        }, null);

        RecipePublicationWatch watch = driver.beginPublicationWatch();
        List<String> seen = new ArrayList<>();
        watch.whenPublished(outcome -> seen.add(outcome.published() + ":"
                + (outcome.failure() == null ? "none" : outcome.failure().getMessage())));
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> driver.start(new Object(), List.of(failing), BUDGET, pot::publish));

        assertEquals("registration failed", thrown.getMessage());
        assertEquals(List.of("false:registration failed"), seen,
                "a failed pass reports rather than leaving the continuation waiting");
        assertFalse(pot.published, "nothing publishes after a round failed");
    }

    @Test
    void aReplacedRoundReportsThatThePassDidNotPublish() {
        FakeArm arm = new FakeArm();
        RecipeRegistrationDriver driver = new RecipeRegistrationDriver(arm);
        FakeManager pot = new FakeManager();
        Object owner = new Object();

        RecipePublicationWatch watch = driver.beginPublicationWatch();
        List<String> seen = new ArrayList<>();
        watch.whenPublished(outcome -> seen.add(outcome.published() + ":"
                + (outcome.failure() == null ? "none" : outcome.failure().getMessage())));
        driver.start(owner, List.of(pot.segment(112)), BUDGET, pot::publish);
        driver.start(owner, List.of(pot.segment(20)), BUDGET, pot::publish);
        driver.endPublicationWatch();

        assertEquals(List.of("false:none"), seen,
                "a round a newer load replaced never published, and that is not a failure");
        assertTrue(pot.published);
    }

    @Test
    void aPassThatStartedNoRoundContinuesAndRepeatsImmediately() {
        RecipeRegistrationDriver driver = new RecipeRegistrationDriver(new FakeArm());

        RecipePublicationWatch watch = driver.beginPublicationWatch();
        List<String> seen = new ArrayList<>();
        watch.whenPublished(outcome -> seen.add(outcome.published() + ":" + outcome.failure()));
        driver.endPublicationWatch();

        assertEquals(List.of("true:null"), seen,
                "a pass with nothing to publish did not change the recipe set, so its work still runs");
        assertTrue(watch.isSettled());
        watch.whenPublished(outcome -> seen.add("again:" + outcome.published()));
        assertEquals(List.of("true:null", "again:true"), seen,
                "a continuation registered after the pass settled runs immediately");
    }

    /** A stand-in for one manager: the entries its round registers and the tail that publishes the set. */
    private static final class FakeManager {

        private final List<String> entries = new ArrayList<>();
        private boolean published;

        private RecipeRegistrationRound.Segment segment(int count) {
            List<String> ids = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                ids.add("recipe_" + index);
            }
            return new RecipeRegistrationRound.Segment(ids, this.entries::add, null);
        }

        private void publish() {
            this.published = true;
        }
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
