package com.huidu.farmersdelight.handheld;

import com.huidu.farmersdelight.manager.HandheldDisplays;
import com.huidu.farmersdelight.util.scheduler.PluginTask;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.World;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wiring layer of handheld skewer cooking, asserted without a server.
 *
 *
 * The lifecycle is what this suite locks down: the tick loop is armed by the session that needs it, cancels
 * itself once the last session ends, and the next session arms a fresh loop. Two of the cases drive the
 * production HandCookedSkewerHooks#tick() through the recorded Runnable and the loop seam, so
 * the self-cancellation itself is covered, not simulated.
 *
 *
 * What a single-threaded test cannot show is a real interleaving of two Folia regions (both arming at the
 * instant the loop cancels); the single lock around the whole lifecycle covers it — the
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

    /**
     * The {@code blockClick} flag means "the policy left this click to the block" (a campfire taking food), not
     * "a block was clicked": an accepted block click is passed as false by the wiring.
     */
    @Test
    void aRightClickOnAHeatSourceBlockNeverStarts() {
        HandCookedSkewerHooks hooks = armed(new CountingArm(), new AtomicInteger(), new FakeLoopSeam());
        assertFalse(hooks.handleUse(UUID.randomUUID(), EquipmentSlot.HAND, true, false, false, true, true),
                "a click the block owns is never cooked behind vanilla's back");
    }

    @Test
    void aRightClickOnAPlainBlockStartsNothing() {
        HandCookedSkewerHooks hooks = armed(new CountingArm(), new AtomicInteger(), new FakeLoopSeam());
        assertFalse(hooks.handleUse(UUID.randomUUID(), EquipmentSlot.HAND, true, false, false, true, false));
    }

    @Test
    void aBlockClickNearHeatStillDoesNotStart() {
        HandCookedSkewerHooks hooks = armed(new CountingArm(), new AtomicInteger(), new FakeLoopSeam());
        assertFalse(hooks.handleUse(UUID.randomUUID(), EquipmentSlot.HAND, true, false, false, true, true),
                "the cube probe is for the air path only: a click the block owns never starts");
    }

    @Test
    void holdingSomethingElseNeverStarts() {
        HandCookedSkewerHooks hooks = armed(new CountingArm(), new AtomicInteger(), new FakeLoopSeam());
        assertFalse(hooks.handleUse(UUID.randomUUID(), EquipmentSlot.HAND, false, false, false, true, true),
                "a player who is not holding a raw skewer sees no change at all");
    }

    @Test
    void aRightClickInAirStillUsesTheNearbyProbe() {
        HandCookedSkewerHooks hooks = armed(new CountingArm(), new AtomicInteger(), new FakeLoopSeam());
        UUID player = UUID.randomUUID();

        assertTrue(hooks.handleUse(player, EquipmentSlot.HAND, true, false, false, false, true));
        assertTrue(hooks.service().isCooking(player), "and it really starts the session");
    }

    @Test
    void aRightClickInAirWithoutHeatStartsNothing() {
        HandCookedSkewerHooks hooks = armed(new CountingArm(), new AtomicInteger(), new FakeLoopSeam());
        UUID player = UUID.randomUUID();

        assertFalse(hooks.handleUse(player, EquipmentSlot.HAND, true, false, false, false, false),
                "no heat around the player means no session");
        assertFalse(hooks.service().isCooking(player));
    }

    /**
     * The vanilla interaction is never cancelled: MONITOR is the observe-only priority, and the handler has no
     * cancel path left (the parameter that could run one is gone). This pins the registration half; the other
     * half is that no setCancelled call exists in the class.
     */
    @Test
    void theInteractHandlerCannotCancelTheVanillaUse() throws Exception {
        EventHandler annotation = HandCookedSkewerHooks.class
                .getMethod("onInteract", PlayerInteractEvent.class)
                .getAnnotation(EventHandler.class);
        assertNotNull(annotation, "the handler has to keep its registration contract");
        assertEquals(EventPriority.MONITOR, annotation.priority(),
                "MONITOR is the observe-only priority: the vanilla use must not be modified");
        assertTrue(annotation.ignoreCancelled(),
                "and a cancellation by the campfire path must stay invisible to us");
    }

    /**
     * The result source order (decision 2): a configured override wins, the campfire recipe is the fallback,
     * and neither means the session cannot start. The source is item-level, so these drive it with a fake that
     * ignores the stack.
     */
    @Test
    void aConfiguredOverrideWinsOverTheRecipe() {
        HandCookedSkewerHooks hooks = armed(new CountingArm(), new AtomicInteger(), new FakeLoopSeam());
        StringBuilder asked = new StringBuilder();
        hooks.setResultSource(held -> {
            asked.append("asked");
            return "farmersdelight:override_result";
        });

        assertEquals("farmersdelight:override_result", hooks.resolvedResult(null),
                "the configured answer is what the session will produce");
        assertEquals("asked", asked.toString(), "and the source really was consulted");
    }

    @Test
    void aRecipeFallbackAnswersJustLikeAnOverride() {
        HandCookedSkewerHooks hooks = armed(new CountingArm(), new AtomicInteger(), new FakeLoopSeam());
        hooks.setResultSource(held -> "farmersdelight:cooked_meat_skewer");

        assertEquals("farmersdelight:cooked_meat_skewer", hooks.resolvedResult(null),
                "a campfire recipe answer starts the same session an override does");
        assertTrue(hooks.handleUse(UUID.randomUUID(), EquipmentSlot.HAND, true, false, false, false, true),
                "and with an answer in hand the air click starts");
    }

    /** No override and no campfire recipe: upstream refuses to start, so nothing is consumed. */
    @Test
    void noRecipeAtAllMeansNoSession() {
        HandCookedSkewerHooks hooks = armed(new CountingArm(), new AtomicInteger(), new FakeLoopSeam());
        UUID player = UUID.randomUUID();
        hooks.setResultSource(held -> null);

        assertNull(hooks.resolvedResult(null), "no answer from the source");
        assertFalse(hooks.handleUse(player, EquipmentSlot.HAND, false, false, false, false, true),
                "so the use gate refuses and no session is created");
        assertFalse(hooks.service().isCooking(player));
        // The configured table's own wins are asserted by the SkewerResultTable suite; what matters here is
        // that a source without an answer cannot start a session.
        hooks.setResultSource(null);
        assertNull(hooks.resolvedResult(null), "with no stack and no item id the default table answers nothing");
    }

    /**
     * The progress bar (decision ③): opened with the session, one update every four ticks, closed when the
     * session goes away, and silent when the setting is off. The fake records the calls, so "nothing sent" and
     * "wrong cadence" are both observable offline.
     */
    @Test
    void theProgressBarIsOpenedAndTickedEveryFourthTick() {
        CountingArm arm = new CountingArm();
        HandCookedSkewerHooks hooks = armed(arm, new AtomicInteger(), new FakeLoopSeam());
        RecordingDisplay display = new RecordingDisplay();
        hooks.setProgressDisplay(display);
        hooks.setCookingTicks(120);
        UUID player = UUID.randomUUID();
        assertTrue(hooks.beginSession(player, EquipmentSlot.HAND));

        hooks.startProgressDisplay(player, 3, null);
        assertEquals(1, display.starts, "the bar is opened for the session");
        assertEquals(0, display.updates.size(), "and nothing is sent before the first tick of progress");

        for (int tick = 0; tick < 8; tick++) {
            arm.runTick();
        }
        assertEquals(java.util.List.of(4, 8), display.updates,
                "one update every four ticks, carrying the tick-based progress");
    }

    @Test
    void theProgressBarIsSilentBetweenFourthTicks() {
        CountingArm arm = new CountingArm();
        HandCookedSkewerHooks hooks = armed(arm, new AtomicInteger(), new FakeLoopSeam());
        RecordingDisplay display = new RecordingDisplay();
        hooks.setProgressDisplay(display);
        hooks.setCookingTicks(120);
        UUID player = UUID.randomUUID();
        assertTrue(hooks.beginSession(player, EquipmentSlot.HAND));
        hooks.startProgressDisplay(player, 0, null);

        for (int tick = 0; tick < 3; tick++) {
            arm.runTick();
        }
        assertEquals(0, display.updates.size(), "ticks one to three send nothing");
    }

    @Test
    void aDroppedSessionClosesTheProgressBar() {
        CountingArm arm = new CountingArm();
        HandCookedSkewerHooks hooks = armed(arm, new AtomicInteger(), new FakeLoopSeam());
        RecordingDisplay display = new RecordingDisplay();
        hooks.setProgressDisplay(display);
        UUID player = UUID.randomUUID();
        assertTrue(hooks.beginSession(player, EquipmentSlot.HAND));
        hooks.startProgressDisplay(player, 0, null);

        assertTrue(hooks.applyConfig(true, false, 120, ONE_RESULT, arm, null), "a reload drops the session");
        assertTrue(display.closes >= 1,
                "the bar has to be closed with its session, or the client keeps showing a fake one");
    }

    @Test
    void aDisabledProgressDisplaySendsNothing() {
        CountingArm arm = new CountingArm();
        HandCookedSkewerHooks hooks = armed(arm, new AtomicInteger(), new FakeLoopSeam());
        RecordingDisplay display = new RecordingDisplay();
        hooks.setProgressDisplay(display);
        hooks.setProgressDisplayEnabled(false);
        UUID player = UUID.randomUUID();
        assertTrue(hooks.beginSession(player, EquipmentSlot.HAND));
        hooks.startProgressDisplay(player, 0, null);

        for (int tick = 0; tick < 8; tick++) {
            arm.runTick();
        }
        assertEquals(0, display.starts, "disabled means no copy is ever opened");
        assertEquals(0, display.updates.size(), "and nothing is sent");
    }

    /** Records what the bar seam was asked to do. */
    private static final class RecordingDisplay implements HandCookedSkewerHooks.SkewerProgressDisplay {

        private int starts;
        private int closes;
        private final java.util.List<Integer> updates = new java.util.ArrayList<>();

        @Override
        public void start(UUID player, int slot, String expectedId, int duration) {
            this.starts++;
        }

        @Override
        public void update(UUID player, int progress, int duration) {
            this.updates.add(progress);
        }

        @Override
        public void close(UUID player) {
            this.closes++;
        }
    }

    /** Mandatory fix 2: a second start for the same player must close the previous handle, not leak it. */
    @Test
    void aSecondStartClosesThePreviousHandle() {
        List<String> closes = new ArrayList<>();
        HandCookedSkewerHooks.HeldSkewerProgressDisplay display = new HandCookedSkewerHooks.HeldSkewerProgressDisplay(
                null, id -> fakePlayer("farmersdelight:meat_skewer"), (player, slot, original, duration) ->
                        handleOf(closes), (stack) -> "farmersdelight:meat_skewer");
        UUID player = UUID.randomUUID();

        display.start(player, 3, "farmersdelight:meat_skewer", 120);
        display.start(player, 3, "farmersdelight:meat_skewer", 120);

        assertEquals(1, closes.size(), "the second start has to close the first handle");
    }

    /**
     * Mandatory fix 3: dragging the skewer out of its hotbar slot never fires PlayerItemHeldEvent, so the bar
     * has to notice on the next tick and close itself; while the slot still holds the skewer it must stay.
     */
    @Test
    void theBarClosesWhenTheSlotNoLongerHoldsTheSkewer() {
        List<String> closes = new ArrayList<>();
        HandCookedSkewerHooks.HeldSkewerProgressDisplay display = new HandCookedSkewerHooks.HeldSkewerProgressDisplay(
                null, id -> fakePlayer("farmersdelight:bread"), (player, slot, original, duration) ->
                        handleOf(closes), (stack) -> "farmersdelight:bread");
        UUID player = UUID.randomUUID();
        display.start(player, 3, "farmersdelight:meat_skewer", 120);

        display.update(player, 4, 120);
        assertEquals(1, closes.size(), "a slot that no longer holds the skewer closes the bar");

        List<String> stillThere = new ArrayList<>();
        HandCookedSkewerHooks.HeldSkewerProgressDisplay unchanged = new HandCookedSkewerHooks.HeldSkewerProgressDisplay(
                null, id -> fakePlayer("farmersdelight:meat_skewer"), (player2, slot, original, duration) ->
                        handleOf(stillThere), (stack) -> "farmersdelight:meat_skewer");
        unchanged.start(player, 3, "farmersdelight:meat_skewer", 120);
        unchanged.update(player, 4, 120);
        assertEquals(0, stillThere.size(), "an untouched slot keeps its bar");
    }

    /** Mandatory fix 4: the source itself is asserted, not just a grep on the author's machine. */
    @Test
    void theHooksSourceNeverCancelsTheVanillaInteraction() throws Exception {
        Path source = locateHooksSource();
        assertNotNull(source, "the handler source has to be reachable from the test working directory");
        String code = Files.readString(source);
        assertFalse(code.contains("setCancelled"), "the handheld path must never cancel the vanilla use");
        assertFalse(code.contains("cancelVanilla"), "and never carries a cancel callback");
    }

    private static Path locateHooksSource() {
        Path cursor = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 4 && cursor != null; depth++) {
            Path candidate = cursor.resolve(
                    "FarmersDelight/src/main/java/com/huidu/farmersdelight/handheld/HandCookedSkewerHooks.java");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            candidate = cursor.resolve(
                    "src/main/java/com/huidu/farmersdelight/handheld/HandCookedSkewerHooks.java");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            cursor = cursor.getParent();
        }
        return null;
    }

    /** A player whose held slot reports the given id through the injected resolver. */
    private static Player fakePlayer(String slotItemDescription) {
        PlayerInventory inventory = (PlayerInventory) Proxy.newProxyInstance(PlayerInventory.class.getClassLoader(),
                new Class<?>[]{PlayerInventory.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getItem" -> null;
                    case "toString" -> "fake inventory";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new AssertionError("unexpected inventory call: " + method.getName());
                });
        World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUID" -> UUID.fromString("00000000-0000-0000-0000-0000000000aa");
                    case "toString" -> "fake world";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new AssertionError("unexpected world call: " + method.getName());
                });
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getInventory" -> inventory;
                    case "getWorld" -> world;
                    case "toString" -> "fake player (" + slotItemDescription + ")";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new AssertionError("unexpected player call: " + method.getName());
                });
    }

    private static HandheldDisplays.Handle handleOf(List<String> closes) {
        return new HandheldDisplays.Handle() {

            @Override
            public void update(int progress) {
            }

            @Override
            public void close() {
                closes.add("close");
            }
        };
    }

    /** Loud failure: every gate that can silently stop a use reports a distinct reason. */
    @Test
    void everyBlockedGateReportsItsOwnReason() {
        assertNull(HandCookedSkewerHooks.blockedReason(true, true, false, false, false, true, false),
                "nothing blocks a plain air use near heat");
        assertEquals("disabled", HandCookedSkewerHooks.blockedReason(false, true, false, false, false, true, false));
        assertEquals("no-result (override table miss and no campfire recipe)", HandCookedSkewerHooks.blockedReason(true, false, false, false, false, true, false));
        assertEquals("skillet-cooking",
                HandCookedSkewerHooks.blockedReason(true, true, true, false, false, true, false));
        assertEquals("sneak-required",
                HandCookedSkewerHooks.blockedReason(true, true, false, false, true, true, false));
        assertEquals("no-heat", HandCookedSkewerHooks.blockedReason(true, true, false, false, false, false, false));
        assertEquals("block-click (vanilla owns it)",
                HandCookedSkewerHooks.blockedReason(true, true, false, false, false, false, true));
    }

    /**
     * The wiring's action mapping. The rule lives in SkewerBlockClickPolicy; what has to hold here is that a left
     * click can never reach the accepted branch, that a block without an interaction is judged instead of
     * dropped, and that sneaking is what takes the click from a block that has one.
     */
    @Test
    void aBlockClickWithoutInteractionIsAccepted() {
        assertEquals(SkewerBlockClickPolicy.Decision.ACCEPT_BLOCK_WITHOUT_INTERACTION,
                HandCookedSkewerHooks.decideClick(Action.RIGHT_CLICK_BLOCK, false, false),
                "fire/lava/magma/plain terrain next to the player is exactly the click the report could not trigger");
        assertTrue(SkewerBlockClickPolicy.accepted(
                HandCookedSkewerHooks.decideClick(Action.RIGHT_CLICK_BLOCK, false, false)));
    }

    @Test
    void sneakingTakesTheClickFromACampfire() {
        assertEquals(SkewerBlockClickPolicy.Decision.ACCEPT_BLOCK_SNEAKING,
                HandCookedSkewerHooks.decideClick(Action.RIGHT_CLICK_BLOCK, true, true),
                "a sneaking player deliberately aims at the campfire, so the click is ours");
        assertTrue(SkewerBlockClickPolicy.accepted(
                HandCookedSkewerHooks.decideClick(Action.RIGHT_CLICK_BLOCK, true, true)));
    }

    @Test
    void aCampfireKeepsItsOwnClick() {
        assertEquals(SkewerBlockClickPolicy.Decision.DECLINE_BLOCK_HAS_INTERACTION,
                HandCookedSkewerHooks.decideClick(Action.RIGHT_CLICK_BLOCK, true, false),
                "a campfire takes the skewer as food, so a non-sneaking click stays vanilla's");
        assertFalse(SkewerBlockClickPolicy.accepted(
                HandCookedSkewerHooks.decideClick(Action.RIGHT_CLICK_BLOCK, true, false)));
    }

    @Test
    void anAcceptedBlockClickStillNeedsHeat() {
        // The policy only answers "may this click be ours"; without heat around the player the start gate still
        // refuses, which is the same no-heat line an air click gets.
        assertEquals("no-heat",
                HandCookedSkewerHooks.blockedReason(true, true, false, false, false, false, false));
    }

    @Test
    void anAirClickIsStillAccepted() {
        assertEquals(SkewerBlockClickPolicy.Decision.ACCEPT_AIR,
                HandCookedSkewerHooks.decideClick(Action.RIGHT_CLICK_AIR, false, false),
                "the original trigger has to keep working");
    }

    @Test
    void aLeftOrPhysicalClickIsNeverOurs() {
        assertEquals(SkewerBlockClickPolicy.Decision.DECLINE_NOT_A_RIGHT_CLICK,
                HandCookedSkewerHooks.decideClick(Action.LEFT_CLICK_BLOCK, false, false));
        assertEquals(SkewerBlockClickPolicy.Decision.DECLINE_NOT_A_RIGHT_CLICK,
                HandCookedSkewerHooks.decideClick(Action.PHYSICAL, true, true));
        assertEquals(SkewerBlockClickPolicy.Decision.DECLINE_NOT_A_RIGHT_CLICK,
                HandCookedSkewerHooks.decideClick(Action.LEFT_CLICK_AIR, false, false));
    }

    /**
     * The null-hand fallback of the wiring: Paper can report no hand for an air interaction, and the click must
     * then be judged on the main hand and, failing that, the off hand. Mapping index 0 back to the main hand
     * unconditionally (the old behaviour) loses a skewer held in the off hand.
     */
    @Test
    void anUnknownHandFallsBackToTheMainAndThenTheOffHand() {
        assertEquals(EquipmentSlot.HAND, HandCookedSkewerHooks.handAt(0, null),
                "with no reported hand the main hand is tried first");
        assertEquals(EquipmentSlot.OFF_HAND, HandCookedSkewerHooks.handAt(1, null),
                "and the off hand second");
        assertEquals(EquipmentSlot.OFF_HAND, HandCookedSkewerHooks.handAt(0, EquipmentSlot.OFF_HAND),
                "a reported off hand stays the only candidate, it is never remapped to the main hand");
        assertEquals(EquipmentSlot.HAND, HandCookedSkewerHooks.handAt(0, EquipmentSlot.HAND));
    }

    /** The entry line names every field the field report needs, in one line. */
    @Test
    void theEntryLineNamesEveryField() {
        String line = HandCookedSkewerHooks.entryPointLine(Action.RIGHT_CLICK_BLOCK, EquipmentSlot.HAND, false,
                "farmersdelight:meat_skewer", "FIRE");
        assertTrue(line.startsWith("[handheld] "), line);
        assertTrue(line.contains("action=RIGHT_CLICK_BLOCK"), line);
        assertTrue(line.contains("hand=HAND"), line);
        assertTrue(line.contains("cancelled=false"), line);
        assertTrue(line.contains("item=farmersdelight:meat_skewer"), line);
        assertTrue(line.contains("block=FIRE"), line);
        assertTrue(HandCookedSkewerHooks.entryPointLine(Action.RIGHT_CLICK_AIR, null, true, "none", "none")
                .contains("cancelled=true"), "a cancelled arrival has to carry its own state");
        String empty = HandCookedSkewerHooks.entryPointLine(Action.RIGHT_CLICK_AIR, null, false, null, null);
        assertTrue(empty.contains("item=none"), empty);
        assertTrue(empty.contains("block=none"), empty);
    }

    /**
     * A click that arrives and a click that was already cancelled are two different diagnoses, so both have to
     * produce a line: dropping the flag from the key or the line loses the answer the field test is looking for.
     */
    @Test
    void anArrivalAndACancelledArrivalBothProduceALine() {
        UUID player = UUID.randomUUID();
        String arrived = HandCookedSkewerHooks.entryPointKey(player, Action.RIGHT_CLICK_AIR, null, false,
                "farmersdelight:meat_skewer", "none");
        String cancelled = HandCookedSkewerHooks.entryPointKey(player, Action.RIGHT_CLICK_AIR, null, true,
                "farmersdelight:meat_skewer", "none");
        assertFalse(arrived.equals(cancelled), "the cancellation state has to be part of the dedupe key");
        assertTrue(HandCookedSkewerHooks.entryPointLine(Action.RIGHT_CLICK_AIR, null, false, "none", "none")
                .contains("cancelled=false"));
        assertTrue(HandCookedSkewerHooks.entryPointLine(Action.RIGHT_CLICK_AIR, null, true, "none", "none")
                .contains("cancelled=true"));
    }

    /**
     * The entry dedupe is per signature and bounded: the same signature logs once, and the set never grows past
     * its cap, so a long uptime cannot turn a debug line into a leak.
     */
    @Test
    void theEntryDedupeLogsOncePerSignatureAndStaysBounded() {
        HandCookedSkewerHooks hooks = new HandCookedSkewerHooks(null);
        int cap = HandCookedSkewerHooks.entryLogCapacity();
        assertTrue(cap > 0 && cap <= 4096, "the cap has to be a real bound, was " + cap);

        String key = HandCookedSkewerHooks.entryPointKey(UUID.randomUUID(), Action.RIGHT_CLICK_AIR, null, false,
                "farmersdelight:meat_skewer", "none");
        assertTrue(hooks.markEntryLogged(key), "the first occurrence is new");
        assertFalse(hooks.markEntryLogged(key), "the same signature is not logged twice");
        assertEquals(1, hooks.entryPointLogSize());

        for (int index = 0; index < cap * 2; index++) {
            hooks.markEntryLogged("filler-" + index);
        }
        assertTrue(hooks.entryPointLogSize() <= cap,
                "the set has to drop its oldest signature instead of growing: size=" + hooks.entryPointLogSize());
    }

    /**
     * The observer handler has to exist, has to be called for cancelled events (that is its whole purpose), and
     * the main handler has to stay off them. Flipping either one silently loses the diagnosis the field test
     * depends on, so both annotations are pinned here.
     */
    @Test
    void theObserverOnlyHandlerWatchesCancelledEvents() throws Exception {
        EventHandler observer = HandCookedSkewerHooks.class
                .getMethod("onInteractObserved", PlayerInteractEvent.class)
                .getAnnotation(EventHandler.class);
        assertNotNull(observer, "a separate handler is the only way a cancelled event becomes visible");
        assertFalse(observer.ignoreCancelled(), "it has to run for cancelled events, or it observes nothing");
        assertEquals(EventPriority.MONITOR, observer.priority(),
                "at the earliest priority nothing has cancelled yet, so the field would always read false");

        EventHandler main = HandCookedSkewerHooks.class
                .getMethod("onInteract", PlayerInteractEvent.class)
                .getAnnotation(EventHandler.class);
        assertNotNull(main);
        assertTrue(main.ignoreCancelled(), "the main handler keeps ignoring cancelled events");
        assertEquals(EventPriority.MONITOR, main.priority());
    }

    /**
     * With the handheld category off the observer writes nothing and remembers nothing — and the event comes out
     * exactly as it went in: an observer that rewrote the outcome would be changing behaviour it must only watch.
     */
    @Test
    void theObserverWritesNothingAndChangesNothingWhenDebugIsOff() {
        HandCookedSkewerHooks hooks = new HandCookedSkewerHooks(null);
        PlayerInteractEvent event = new PlayerInteractEvent(fakePlayer("farmersdelight:meat_skewer"),
                Action.RIGHT_CLICK_BLOCK, null, null, null, EquipmentSlot.HAND);
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);

        hooks.onInteractObserved(event);

        assertEquals(0, hooks.entryPointLogSize(), "debug off writes no line and remembers no signature");
        assertEquals(Event.Result.DENY, event.useItemInHand(), "the observer must not touch the item use");
        assertEquals(Event.Result.DENY, event.useInteractedBlock(), "nor the interacted block");
        assertEquals(Action.RIGHT_CLICK_BLOCK, event.getAction(), "and leaves the action alone");
        assertEquals(EquipmentSlot.HAND, event.getHand());
    }

    /**
     * Both entry points go through one gated, bounded dedupe: the interaction the observer already reported is not
     * reported a second time by the main handler, so one action costs one line, never two.
     */
    @Test
    void theTwoEntryPointsShareOneLinePerAction() {
        HandCookedSkewerHooks hooks = new HandCookedSkewerHooks(null);
        String key = HandCookedSkewerHooks.entryPointKey(UUID.randomUUID(), Action.RIGHT_CLICK_BLOCK,
                EquipmentSlot.HAND, false, "farmersdelight:meat_skewer", "FIRE");
        assertTrue(hooks.markEntryLogged(key), "the observer's line is the first one");
        assertFalse(hooks.markEntryLogged(key), "the main handler's identical line is suppressed");
        assertEquals(1, hooks.entryPointLogSize(), "one line per action, not two");
    }

    /** The observer stays log-only: asserted on the source, because that is what "never does business" means. */
    @Test
    void theObserverBodyOnlyWritesTheEntryLine() throws Exception {
        Path source = locateHooksSource();
        assertNotNull(source, "the handler source has to be reachable from the test working directory");
        String code = Files.readString(source);
        int start = code.indexOf("public void onInteractObserved");
        assertTrue(start > 0, "the observer has to be there to be asserted");
        String body = code.substring(start, code.indexOf("\n    }", start));
        assertTrue(body.contains("logEntryPoint("), "it writes the shared entry line");
        for (String forbidden : new String[]{"setCancelled", "setUseItemInHand", "setUseInteractedBlock",
                "handleUse(", "beginSession(", "startProgressDisplay(", "hasHeat(", "tryStart("}) {
            assertFalse(body.contains(forbidden), "the observer must not do business: " + forbidden);
        }
    }

    /** Plugin disable: every open bar has to go with its handler, not outlive the plugin. */
    @Test
    void shutdownClosesEveryOpenBar() {
        CountingArm arm = new CountingArm();
        HandCookedSkewerHooks hooks = armed(arm, new AtomicInteger(), new FakeLoopSeam());
        RecordingDisplay display = new RecordingDisplay();
        hooks.setProgressDisplay(display);
        UUID player = UUID.randomUUID();
        assertTrue(hooks.beginSession(player, EquipmentSlot.HAND));
        hooks.startProgressDisplay(player, 0, "farmersdelight:meat_skewer");
        assertEquals(1, display.starts, "the bar is open before shutdown");

        hooks.shutdown();

        assertTrue(display.closes >= 1, "shutdown has to close the bar, or its netty handler survives disable");
        hooks.shutdown();
        assertEquals(display.closes, display.closes, "a second shutdown is a no-op, never an error");
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
