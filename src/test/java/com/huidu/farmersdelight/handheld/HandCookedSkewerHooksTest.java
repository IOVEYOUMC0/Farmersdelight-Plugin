package com.huidu.farmersdelight.handheld;

import com.huidu.farmersdelight.util.scheduler.PluginTask;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wiring layer of handheld skewer cooking, asserted without a server.
 *
 *
 * The session lifecycle is the part this suite exists for: the tick loop is armed by the session that needs it
 * and cancels itself when that session ends, so the second and every later skewer has to arm a fresh loop. A
 * loop armed once at config time would look correct in every test that only ever cooks one skewer, which is
 * exactly how the first version of this code went wrong.
 *
 *
 * Everything needing a real {@code Player}, a heat source or the region scheduler is on the real-server
 * checklist instead: no fake-green test here pretends otherwise.
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
        // Order: the skillet check is evaluated first, so an already-cooking skillet wins even when everything
        // else about the skewer is ready.
        assertFalse(HandCookedSkewerHooks.mayStart(true, true, true, true, false),
                "a player whose skillet is cooking must not start a skewer session");
        // require-sneak: false (the 1.4 default) starts without sneaking, true requires it.
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
        assertNotSame(hooks.service(), null);
        assertEquals(1, arm.armed, "a reload cancels the old loop and leaves arming to the next session");
    }

    /** The regression this suite was missing: the loop cancels itself, so the next skewer must arm a new one. */
    @Test
    void aSessionAfterTheLoopCancelledIsArmedAgainAndCooks() {
        AtomicInteger cooked = new AtomicInteger();
        CountingArm arm = new CountingArm();
        HandCookedSkewerHooks hooks = new HandCookedSkewerHooks(null);
        assertTrue(hooks.applyConfig(true, false, 120, ONE_RESULT, arm, player -> cooked.incrementAndGet()));
        UUID player = UUID.randomUUID();

        assertTrue(hooks.beginSession(player, EquipmentSlot.HAND));
        assertEquals(1, arm.armed);
        assertTrue(cookOne(hooks, player), "the first skewer finishes");
        assertEquals(1, cooked.get());
        // What the loop itself does once the last session ends: cancel. Nothing else can happen, so the next
        // session has to arm a fresh loop or it never advances (the P0 this test locks down).
        arm.cancelLast();
        assertEquals(0, arm.armed);

        assertTrue(hooks.beginSession(player, EquipmentSlot.HAND), "the loop has to be armable again");
        assertEquals(1, arm.armed, "the second session arms a new loop");
        assertTrue(cookOne(hooks, player), "and the second skewer actually finishes");
        assertEquals(2, cooked.get());
    }

    @Test
    void threeSessionsInARowEachCookOneSkewer() {
        AtomicInteger cooked = new AtomicInteger();
        CountingArm arm = new CountingArm();
        HandCookedSkewerHooks hooks = new HandCookedSkewerHooks(null);
        assertTrue(hooks.applyConfig(true, false, 120, ONE_RESULT, arm, player -> cooked.incrementAndGet()));
        UUID player = UUID.randomUUID();

        for (int session = 1; session <= 3; session++) {
            assertTrue(hooks.beginSession(player, EquipmentSlot.HAND), "session " + session);
            assertEquals(1, arm.armed, "session " + session + " has exactly one loop");
            assertTrue(cookOne(hooks, player), "session " + session + " cooks");
            assertEquals(session, cooked.get(), "one skewer per session");
            arm.cancelLast();
        }
    }

    /** Advances one session through its full cooking time; true when that finished the skewer. */
    private static boolean cookOne(HandCookedSkewerHooks hooks, UUID player) {
        HandCookedSkewerService active = hooks.service();
        boolean finished = false;
        for (int tick = 0; tick < HandCookedSkewerService.COOKING_TICKS; tick++) {
            finished = active.tick(player);
        }
        return finished;
    }

    /** Counts arming and lets a test cancel the loop the way the loop cancels itself when idle. */
    private static final class CountingArm implements HandCookedSkewerHooks.TaskArm {

        private int armed;
        private CountingTask last;

        @Override
        public PluginTask arm(Runnable tick) {
            armed++;
            last = new CountingTask();
            return last;
        }

        private void cancelLast() {
            last.cancel();
            armed--;
        }
    }

    private static final class CountingTask implements PluginTask {

        private volatile boolean cancelled;

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }
}
