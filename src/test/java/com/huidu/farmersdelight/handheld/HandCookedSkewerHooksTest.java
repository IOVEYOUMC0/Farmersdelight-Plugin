package com.huidu.farmersdelight.handheld;

import com.huidu.farmersdelight.util.scheduler.PluginTask;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wiring layer of handheld skewer cooking, asserted without a server.
 *
 *
 * What can be checked offline is exactly the decision layer: whether the path arms at all (the config switch
 * and the result table), whether a raw id this table does not know can start, and who wins the right-click when
 * the skillet is already cooking. Everything that needs a real {@code Player}, a heat source or the region
 * scheduler is on the real-server checklist in the task report — no fake-green test here pretends otherwise.
 */
class HandCookedSkewerHooksTest {

    private static final Map<String, String> ONE_RESULT = Map.of(
            "farmersdelight:meat_skewer", "farmersdelight:cooked_meat_skewer");

    @Test
    void aDisabledSwitchArmsNothing() {
        CountingArm arm = new CountingArm();
        HandCookedSkewerHooks hooks = new HandCookedSkewerHooks(null);

        boolean armed = hooks.applyConfig(false, false, 120, ONE_RESULT, arm);

        assertFalse(armed, "enabled: false must not arm the path");
        assertNull(hooks.service(), "a disabled path has no session bookkeeping");
        assertEquals(0, arm.armed, "a disabled path must not schedule the tick task");
        assertFalse(HandCookedSkewerHooks.mayStart(armed, true, false, true, false),
                "an unarmed path never starts a session");
    }

    @Test
    void anUnknownHeldItemNeverStarts() {
        SkewerResultTable table = new SkewerResultTable(ONE_RESULT);

        assertNull(table.resolve("farmersdelight:vegetable_skewer"),
                "the fixture only knows the meat skewer");
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

        assertFalse(hooks.applyConfig(true, false, 120, null, arm), "null results must not arm the path");
        assertFalse(hooks.applyConfig(true, false, 120, Map.of(), arm), "an empty table must not arm either");
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
    void reArmingOnReloadKeepsTheSingleTickTask() {
        CountingArm arm = new CountingArm();
        HandCookedSkewerHooks hooks = new HandCookedSkewerHooks(null);

        assertTrue(hooks.applyConfig(true, false, 120, ONE_RESULT, arm));
        HandCookedSkewerService first = hooks.service();
        assertTrue(hooks.applyConfig(true, false, 20, ONE_RESULT, arm), "a reload keeps the path armed");

        assertEquals(1, arm.armed, "the tick task must be armed once, not once per reload");
        assertFalse(first.enabled(), "the previous service is disabled when the config is re-applied");
        assertNotSame(first, hooks.service(), "a reload builds a fresh session table");
        assertEquals(-1, hooks.service().remainingTicks(null), "a fresh table has no sessions");
    }

    /** Counts arming so "off means nothing was scheduled" is assertable. */
    private static final class CountingArm implements HandCookedSkewerHooks.TaskArm {

        private int armed;

        @Override
        public PluginTask arm(Runnable tick) {
            armed++;
            return new PluginTask() {

                private volatile boolean cancelled;

                @Override
                public void cancel() {
                    cancelled = true;
                }

                @Override
                public boolean isCancelled() {
                    return cancelled;
                }
            };
        }
    }
}
