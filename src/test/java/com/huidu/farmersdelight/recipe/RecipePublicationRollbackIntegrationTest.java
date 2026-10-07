package com.huidu.farmersdelight.recipe;

import com.huidu.farmersdelight.util.scheduler.PluginTask;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a readiness pass leaves behind when one of its components fails instead of publishing.
 *
 *
 * The pass publishes each component from its own round tail, so the interesting case is the second one failing
 * after the first already published: without a common restore point the plugin would keep serving a cooking pot
 * set from the new files next to a cutting board set from the old ones. The driver, the watch and the rollback
 * are the real ones here; the two components are stand-ins, so the case runs offline.
 */
class RecipePublicationRollbackIntegrationTest {

    private static final int BUDGET = 32;

    @Test
    void aFailureInTheSecondComponentPutsBothBackAndRebuildsTheIndex() {
        FakeArm arm = new FakeArm();
        RecipeRegistrationDriver driver = new RecipeRegistrationDriver(arm);
        List<String> pot = new ArrayList<>(List.of("old_pot"));
        List<String> board = new ArrayList<>(List.of("old_board"));
        AtomicInteger indexRebuilds = new AtomicInteger();
        List<String> reported = new ArrayList<>();

        RecipePublicationRollback rollback = new RecipePublicationRollback(List.of(
                () -> replace(pot, "old_pot"),
                () -> replace(board, "old_board"),
                indexRebuilds::incrementAndGet));

        RecipePublicationWatch watch = driver.beginPublicationWatch();
        // Registered before the load, like the readiness pass does: a load that throws still has to report.
        watch.whenPublished(outcome -> {
            reported.add(outcome.published() + ":"
                    + (outcome.failure() == null ? "none" : outcome.failure().getMessage()));
            if (!outcome.published()) {
                rollback.restore();
            }
        });

        driver.start(new Object(), List.of(segment(List.of("a"), () -> replace(pot, "new_pot"))), BUDGET, null);
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                driver.start(new Object(), List.of(new RecipeRegistrationRound.Segment(List.of("b"), id -> {
                    throw new IllegalStateException("board registration failed");
                }, () -> replace(board, "new_board"))), BUDGET, null));
        driver.endPublicationWatch();

        assertEquals("board registration failed", thrown.getMessage());
        assertEquals(List.of("false:board registration failed"), reported);
        assertEquals(List.of("old_pot"), pot,
                "the cooking pot published the new files before the failure, so the pass has to put it back");
        assertEquals(List.of("old_board"), board);
        assertEquals(1, indexRebuilds.get(), "the derived index is rebuilt once, after the sets are back");
        assertTrue(rollback.isRestored());
    }

    @Test
    void aPassThatPublishesEverythingKeepsTheNewSets() {
        FakeArm arm = new FakeArm();
        RecipeRegistrationDriver driver = new RecipeRegistrationDriver(arm);
        List<String> pot = new ArrayList<>(List.of("old_pot"));
        List<String> board = new ArrayList<>(List.of("old_board"));
        AtomicInteger indexRebuilds = new AtomicInteger();
        List<String> reported = new ArrayList<>();

        RecipePublicationRollback rollback = new RecipePublicationRollback(List.of(
                () -> replace(pot, "old_pot"),
                () -> replace(board, "old_board"),
                indexRebuilds::incrementAndGet));

        RecipePublicationWatch watch = driver.beginPublicationWatch();
        watch.whenPublished(outcome -> {
            reported.add(outcome.published() + ":" + outcome.failure());
            if (!outcome.published()) {
                rollback.restore();
            }
        });

        driver.start(new Object(), List.of(segment(List.of("a"), () -> replace(pot, "new_pot"))), BUDGET, null);
        driver.start(new Object(), List.of(segment(List.of("b"), () -> replace(board, "new_board"))), BUDGET, null);
        driver.endPublicationWatch();

        assertEquals(List.of("true:null"), reported);
        assertEquals(List.of("new_pot"), pot);
        assertEquals(List.of("new_board"), board);
        assertEquals(0, indexRebuilds.get(), "a published pass is not rolled back");
        assertEquals(0, arm.arms);
    }

    private static RecipeRegistrationRound.Segment segment(List<String> ids, Runnable tail) {
        return new RecipeRegistrationRound.Segment(ids, id -> { }, tail);
    }

    private static void replace(List<String> state, String value) {
        state.clear();
        state.add(value);
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
    }
}
