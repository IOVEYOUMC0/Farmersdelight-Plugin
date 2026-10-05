package com.huidu.farmersdelight.handheld;

import com.huidu.farmersdelight.util.scheduler.PluginTask;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wiring layer of handheld skewer cooking, asserted without a server.
 *
 *
 * The lifecycle is what this suite locks down: the tick loop is armed by the session that needs it, cancels
 * itself once the last session ends, and the next session arms a fresh loop. Two of the cases drive the
 * production {@link HandCookedSkewerHooks#tick()} through the recorded {@code Runnable} and the loop seam, so
 * the self-cancellation itself is covered, not simulated.
 *
 *
 * What a single-threaded test cannot show is a real interleaving of two Folia regions (both arming at the
 * instant the loop cancels); that is covered by code review of the one lock around the whole lifecycle — the
 * tests below assert the sequential compositions that the lock has to preserve.
 */
class HandCookedSkewerHooksTest {

    private static final Map<String, String> ONE_RESULT = Map.of(
            "farmersdelight:meat_skewer", "farmersdelight:cooked_meat_skewer");

    @Test
    void aDisabledSwitchArmsNothing() {
        CountingArm arm = new CountingArm();
        HandCookedSkewerHooks hooks = new HandCookedSkewerHooks(null);

        boolean armed = hooks.applyConfig(false, false, 120, ONE_RESULT, arm, null);

        assertFalse(armed, "enabled: false must not arm the path");
        assertNull(hooks.service(), "a disabled path has no session bookkeeping");
        assertEquals(0, arm.armed, "a disabled path must not schedule the tick task");
        assertFalse(hooks.beginSession(UUID.randomUUID(), EquipmentSlot.HAND),
                "a disabled path cannot start a session");
        assertEquals(0, arm.armed, "and still must not schedule one");
        assertFalse(HandCookedSkewerHooks.mayStart(armed, true, false, true, false),
                "an unarmed path never starts a session");
    }

    @Test
    void anUnknownHeldItemNeverStarts() {
        SkewerResultTable table = new SkewerResultTable(ONE_RESULT);

        assertNull(table.resolve("farmersdelight:vegetable_skewer"), "the fixture only knows the meat skewer");
        boolean hasResult = table.cooks("farmersdelight:vegetable_skewer");

        assertFalse(HandCookedSkewerHooks.mayStart(true, hasResult, false, true, false),
                "an id this table does not cook must not start a session");
        assertTrue(HandCookedSkewerHooks.mayStart(true, table.cooks("farmersdelight:meat_skewer"),
                false, true, false), "the configured id still starts");
    }

    @Test
    void aMissingMappingLeavesThePathInertInsteadOfFailing() {
        CountingArm arm = new CountingArm();
        HandCookedSkewerHooks hooks = new HandCookedSkewerHooks(null);

        assertFalse(hooks.applyConfig(true, false, 120, null, arm, null), "null results must not arm the path");
        assertFalse(hooks.applyConfig(true, false, 120, Map.of(), arm, null), "an empty table must not arm either");
        assertNull(hooks.service());
        assertEquals(0, arm.armed, "nothing is cookable, so no tick task is scheduled");
        assertEquals(0, new SkewerResultTable(null).size(), "a null configuration is an empty table, not a failure");
    }

    @Test
    void theSkilletKeepsTheRightClickAndSneakingIsOptional() {
        assertFalse(HandCookedSkewerHooks.mayStart(true, true, true, true, false),
                "a player whose skillet is cooking must not start a skewer session");
        assertTrue(HandCookedSkewerHooks.mayStart(true, true, false, false, false));
        assertFalse(HandCookedSkewerHooks.mayStart(true, true, false, false, true));
        assertTrue(HandCookedSkewerHooks.mayStart(true, true, false, true, true));
    }

    @Test
    void theTickLoopIsArmedPerSessionAndNeverTwiceAtOnce() {
        CountingArm arm = new CountingArm();
        HandCookedSkewerHooks hooks = new HandCookedSkewerHooks(null);
        assertTrue(hooks.applyConfig(true, false, 120, ONE_RESULT, arm, null));

        assertEquals(0, arm.armed, "reading the config alone must not start a tick loop");

        UUID player = UUID.randomUUID();
        assertTrue(hooks.beginSession(player, EquipmentSlot.HAND));
        assertEquals(1, arm.armed, "the first session arms the loop");

        assertFalse(hooks.beginSession(player, EquipmentSlot.HAND),
                "the same player cannot start a second session while one is running");
        assertEquals(1, arm.armed, "and must not arm a second loop");

        assertTrue(hooks.applyConfig(true, false, 20, ONE_RESULT, arm, null), "a reload keeps the path armed");
        assertEquals(1, arm.armed, "a reload cancels the old loop and leaves arming to the next session");
        assertEquals(-1, hooks.service().remainingTicks(player),
                "and drops the sessions it can no longer advance");
    }

    /**
     * The guard in ensureTaskLocked: a loop that is still running is reused, never re-armed. Without the
     * guard the second player would leave two live loops behind, which shows up as arm/live going to 2 here
     * (and, in a real server, as a skewer cooking in half the time).
     */
    @Test
    void aSecondPlayersSessionJoinsTheRunningLoopInsteadOfArmingAnother() {
        AtomicInteger cooked = new AtomicInteger();
        CountingArm arm = new CountingArm();
        FakeLoopSeam seam = new FakeLoopSeam();
        HandCookedSkewerHooks hooks = armed(arm, cooked, seam);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertTrue(hooks.beginSession(first, EquipmentSlot.HAND));
        assertEquals(1, arm.armed);
        assertEquals(1, arm.live, "one loop runs");

        assertTrue(hooks.beginSession(second, EquipmentSlot.HAND), "a second player starts their own session");
        assertEquals(1, arm.armed, "the running loop is reused, not armed again");
        assertEquals(1, arm.live, "and there is still exactly one of them");

        for (int tick = 0; tick < HandCookedSkewerService.COOKING_TICKS; tick++) {
            arm.runTick();
        }
        assertEquals(2, cooked.get(), "the one loop advanced both sessions");
        assertEquals(120 * 2, seam.dispatches, "each of the 120 iterations dispatched both players");
    }

    /** The regression: the production loop cancels itself, so the next skewer must arm a fresh one. */
    @Test
    void theRealLoopCancelsItselfAndTheNextSessionArmsAFreshOne() {
        AtomicInteger cooked = new AtomicInteger();
        CountingArm arm = new CountingArm();
        HandCookedSkewerHooks hooks = armed(arm, cooked, new FakeLoopSeam());
        UUID player = UUID.randomUUID();

        assertTrue(hooks.beginSession(player, EquipmentSlot.HAND));
        assertEquals(1, arm.live, "one loop is running");
        assertTrue(arm.runLoopFor(hooks, player), "120 real loop ticks finish the first skewer");
        assertEquals(1, cooked.get());
        assertTrue(arm.lastCancelled(), "the loop cancels itself once the last session ended");
        assertEquals(0, arm.live, "no loop is left behind");

        assertTrue(hooks.beginSession(player, EquipmentSlot.HAND), "the next session has to be startable");
        assertEquals(2, arm.armed, "each session arms one loop");
        assertEquals(1, arm.live, "and never more than one at a time");
        assertTrue(arm.runLoopFor(hooks, player), "the second skewer actually finishes");
        assertEquals(2, cooked.get());
    }

    @Test
    void threeSessionsInARowEachCookOneSkewer() {
        AtomicInteger cooked = new AtomicInteger();
        CountingArm arm = new CountingArm();
        HandCookedSkewerHooks hooks = armed(arm, cooked, new FakeLoopSeam());
        UUID player = UUID.randomUUID();

        for (int session = 1; session <= 3; session++) {
            assertTrue(hooks.beginSession(player, EquipmentSlot.HAND), "session " + session);
            assertEquals(session, arm.armed, "each session arms one loop");
            assertEquals(1, arm.live, "session " + session + " runs exactly one loop");
            assertTrue(arm.runLoopFor(hooks, player), "session " + session + " cooks");
            assertEquals(session, cooked.get(), "one skewer per session");
        }
    }

    /** A reload must not leave hand entries behind: they would keep the loop awake with nothing to advance. */
    @Test
    void aReloadDropsSessionsSoTheLoopCanGoIdle() {
        CountingArm arm = new CountingArm();
        FakeLoopSeam seam = new FakeLoopSeam();
        HandCookedSkewerHooks hooks = armed(arm, new AtomicInteger(), seam);
        UUID player = UUID.randomUUID();
        assertTrue(hooks.beginSession(player, EquipmentSlot.HAND));
        assertEquals(1, arm.armed);

        assertTrue(hooks.applyConfig(true, false, 120, ONE_RESULT, arm, null));
        assertEquals(0, arm.live, "a reload cancels the loop and drops the sessions it was advancing");
        int dispatchedBefore = seam.dispatches;
        arm.runTick();
        assertEquals(dispatchedBefore, seam.dispatches,
                "nothing may be dispatched after a reload: a leftover hand entry would be advanced forever");
        assertEquals(1, arm.armed, "the idle loop must not arm itself again");
        assertTrue(arm.lastCancelled(), "and stays cancelled with nothing to advance");
    }

    /** The live defect: a right click on a heat source block starts cooking and cancels the vanilla use. */
    @Test
    void aRightClickOnAHeatSourceBlockStartsCookingAndCancelsTheVanillaUse() {
        HandCookedSkewerHooks hooks = armed(new CountingArm(), new AtomicInteger(), new FakeLoopSeam());
        UUID player = UUID.randomUUID();
        AtomicInteger cancels = new AtomicInteger();

        assertTrue(hooks.handleUse(player, EquipmentSlot.HAND, true, false, false,
                        true, true, false, cancels::incrementAndGet),
                "a heat source block under the cursor starts a session even with no heat around the player");
        assertEquals(1, cancels.get(), "and the vanilla use is cancelled so the fire cannot take the skewer");
        assertTrue(hooks.service().isCooking(player), "the session really runs");
    }

    @Test
    void aRightClickOnAPlainBlockStartsNothingAndLeavesTheUseAlone() {
        HandCookedSkewerHooks hooks = armed(new CountingArm(), new AtomicInteger(), new FakeLoopSeam());
        AtomicInteger cancels = new AtomicInteger();

        assertFalse(hooks.handleUse(UUID.randomUUID(), EquipmentSlot.HAND, true, false, false,
                true, false, false, cancels::incrementAndGet));
        assertEquals(0, cancels.get(), "a block that is not a heat source keeps its own interaction");
    }

    @Test
    void aBlockClickThatIsOnlyNearHeatStartsWithoutCancellingTheUse() {
        HandCookedSkewerHooks hooks = armed(new CountingArm(), new AtomicInteger(), new FakeLoopSeam());
        AtomicInteger cancels = new AtomicInteger();

        assertTrue(hooks.handleUse(UUID.randomUUID(), EquipmentSlot.HAND, true, false, false,
                true, false, true, cancels::incrementAndGet));
        assertEquals(0, cancels.get(), "the cube probe is upstream parity; a plain block is still not cancelled");
    }

    @Test
    void holdingSomethingElseNeverStartsOrCancels() {
        HandCookedSkewerHooks hooks = armed(new CountingArm(), new AtomicInteger(), new FakeLoopSeam());
        AtomicInteger cancels = new AtomicInteger();

        assertFalse(hooks.handleUse(UUID.randomUUID(), EquipmentSlot.HAND, false, false, false,
                true, true, true, cancels::incrementAndGet));
        assertEquals(0, cancels.get(), "a player who is not holding a raw skewer sees no change at all");
    }

    @Test
    void aRightClickInAirStillUsesTheNearbyProbe() {
        HandCookedSkewerHooks hooks = armed(new CountingArm(), new AtomicInteger(), new FakeLoopSeam());
        UUID player = UUID.randomUUID();
        AtomicInteger cancels = new AtomicInteger();

        assertTrue(hooks.handleUse(player, EquipmentSlot.HAND, true, false, false,
                false, false, true, cancels::incrementAndGet));
        assertEquals(0, cancels.get(), "right clicking air never cancels anything");
    }

    private static HandCookedSkewerHooks armed(CountingArm arm, AtomicInteger cooked, FakeLoopSeam seam) {
        HandCookedSkewerHooks hooks = new HandCookedSkewerHooks(null);
        hooks.setLoopSeam(seam);
        assertTrue(hooks.applyConfig(true, false, 120, ONE_RESULT, arm, player -> cooked.incrementAndGet()));
        return hooks;
    }

    /** Stands in for the server + entity scheduler so the production tick() loop can run offline. */
    private static final class FakeLoopSeam implements HandCookedSkewerHooks.LoopSeam {

        private int dispatches;
        private final Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "toString" -> "fake player";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new AssertionError("unexpected Player call: " + method.getName());
                });

        @Override
        public Player player(UUID id) {
            return player;
        }

        @Override
        public void dispatch(Player target, Runnable task) {
            // Same thread on purpose: the production path runs the task inline off Folia, and running it here
            // is what makes the loop's own cancellation observable. The count is how a test tells "the loop
            // had nothing to advance" from "the loop advanced a stale entry".
            assertSame(player, target);
            dispatches++;
            task.run();
        }
    }

    /** Records the loop Runnable so a test can drive the production tick(), and counts arming. */
    private static final class CountingArm implements HandCookedSkewerHooks.TaskArm {

        private int armed;
        private int live;
        private CountingTask last;

        @Override
        public PluginTask arm(Runnable tick) {
            armed++;
            live++;
            last = new CountingTask(this, tick);
            return last;
        }

        private void runTick() {
            last.run();
        }

        /**
         * Drives the production loop until the session finishes, then one more iteration so the loop can
         * notice that nothing is left and cancel itself — exactly what the scheduler would do.
         */
        private boolean runLoopFor(HandCookedSkewerHooks hooks, UUID player) {
            CountingTask task = last;
            for (int tick = 0; tick < HandCookedSkewerService.COOKING_TICKS; tick++) {
                if (task.cancelled) {
                    return false;
                }
                task.run();
            }
            boolean finished = hooks.service() == null || !hooks.service().isCooking(player);
            if (finished && !task.cancelled) {
                task.run();
            }
            return finished;
        }

        private boolean lastCancelled() {
            return last.cancelled;
        }
    }

    private static final class CountingTask implements PluginTask {

        private final CountingArm owner;
        private final Runnable tick;
        private volatile boolean cancelled;

        private CountingTask(CountingArm owner, Runnable tick) {
            this.owner = owner;
            this.tick = tick;
        }

        private void run() {
            tick.run();
        }

        @Override
        public void cancel() {
            if (!cancelled) {
                cancelled = true;
                owner.live--;
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }
}
