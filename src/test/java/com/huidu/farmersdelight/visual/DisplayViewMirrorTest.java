package com.huidu.farmersdelight.visual;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The viewer side of display culling, driven without a server: which displays a pass is allowed to look at,
 * how much of its budget it may spend, when a viewer is told a new range, and what is dropped when a player
 * leaves a world or the server.
 *
 * The packet itself needs CraftEngine, but every decision in front of it is made here, so a pass that scans
 * the whole world, ignores its budget or forgets to drop what a player saw is caught offline.
 */
class DisplayViewMirrorTest {

    private static final UUID WORLD = UUID.nameUUIDFromBytes("world".getBytes(StandardCharsets.UTF_8));
    private static final UUID OTHER_WORLD = UUID.nameUUIDFromBytes("other".getBytes(StandardCharsets.UTF_8));
    private static final UUID PLAYER = UUID.nameUUIDFromBytes("player".getBytes(StandardCharsets.UTF_8));
    private static final String FENCE = "minecraft:mangrove_fence";
    private static final String GATE = "minecraft:mangrove_fence_gate";

    private static DisplayViewSettings settings(double distance, int budget) {
        return new DisplayViewSettings(distance, 8.0D, budget, Map.of(
                FENCE, new DisplayViewSettings.Type(true, distance),
                GATE, new DisplayViewSettings.Type(true, distance)));
    }

    private static UUID player(int index) {
        return UUID.nameUUIDFromBytes(("player-" + index).getBytes(StandardCharsets.UTF_8));
    }

    /** Records every range a pass sent, keyed by display, so tests can assert what each viewer was told. */
    private static final class RecordingSink implements DisplayViewMirror.Sink {

        private final Map<Integer, List<Float>> ranges = new java.util.LinkedHashMap<>();

        @Override
        public void send(int entityId, float range) {
            ranges.computeIfAbsent(entityId, key -> new ArrayList<>()).add(range);
        }

        List<Float> of(int entityId) {
            return ranges.getOrDefault(entityId, List.of());
        }

        int count() {
            int total = 0;
            for (List<Float> values : ranges.values()) {
                total += values.size();
            }
            return total;
        }

        Set<Integer> ids() {
            return new HashSet<>(ranges.keySet());
        }
    }

    /** Only the displays the record holds near the player may be examined; a world-wide scan is not a pass. */
    @Test
    void aPassOnlyExaminesTheDisplaysTheRecordHoldsNearThePlayer() {
        DisplayViewMirror mirror = new DisplayViewMirror();
        mirror.register(WORLD, 1, FENCE, 10.0D, 64.0D, 10.0D, 1.0F);
        mirror.register(WORLD, 2, FENCE, 5000.0D, 64.0D, 5000.0D, 1.0F);
        mirror.register(OTHER_WORLD, 3, FENCE, 10.0D, 64.0D, 10.0D, 1.0F);
        RecordingSink sink = new RecordingSink();

        DisplayViewMirror.PassResult result =
                mirror.pass(PLAYER, WORLD, 0.0D, 64.0D, 0.0D, settings(64.0D, 64), sink);

        assertEquals(1, result.checked(), "only the display inside the player's window is examined");
        assertEquals(Set.of(1), sink.ids(), "a display far away or in another world is left alone");
        assertEquals(List.of(1.0F), sink.of(1), "and the one inside the window keeps its base range");
    }

    /** A dense field of displays may not make one pass proportional to the field. */
    @Test
    void aPassNeverExceedsThePerPlayerBudget() {
        DisplayViewMirror mirror = new DisplayViewMirror();
        for (int i = 0; i < 400; i++) {
            mirror.register(WORLD, i + 1, FENCE, (i % 20) * 2.0D, 64.0D, (i / 20) * 2.0D, 1.0F);
        }
        int budget = 12;
        DisplayViewSettings settings = settings(128.0D, budget);

        int total = 0;
        int players = 8;
        for (int index = 0; index < players; index++) {
            RecordingSink sink = new RecordingSink();
            DisplayViewMirror.PassResult result =
                    mirror.pass(player(index), WORLD, 0.0D, 64.0D, 0.0D, settings, sink);
            assertTrue(result.checked() <= budget,
                    "one player's pass checks " + result.checked() + " displays, over the budget " + budget);
            total += result.checked();
        }
        assertTrue(total <= budget * players, "the pass follows budget times players, not players times displays");
    }

    /** A budget below the number of displays a player sees must delay a range, not lose it. */
    @Test
    void aSmallBudgetStillReachesEveryDisplayOverSeveralPasses() {
        DisplayViewMirror mirror = new DisplayViewMirror();
        int displays = 400;
        for (int i = 0; i < displays; i++) {
            mirror.register(WORLD, i + 1, FENCE, (i % 20) * 2.0D, 64.0D, (i / 20) * 2.0D, 1.0F);
        }
        DisplayViewSettings settings = settings(128.0D, 12);
        Set<Integer> told = new HashSet<>();
        for (int pass = 0; pass < displays; pass++) {
            RecordingSink sink = new RecordingSink();
            mirror.pass(PLAYER, WORLD, 0.0D, 64.0D, 0.0D, settings, sink);
            told.addAll(sink.ids());
            if (told.size() == displays) {
                break;
            }
        }
        assertEquals(displays, told.size(), "a rotating refresh reaches the whole set instead of starving the tail");
    }

    /** A viewer is told once, and only when the answer changed: that is what the hysteresis is for. */
    @Test
    void aDisplayThatDoesNotMoveIsToldOnlyWhenTheAnswerChanges() {
        DisplayViewMirror mirror = new DisplayViewMirror();
        mirror.register(WORLD, 7, FENCE, 0.0D, 64.0D, 0.0D, 1.0F);
        DisplayViewSettings settings = settings(64.0D, 64);
        RecordingSink sink = new RecordingSink();

        mirror.pass(PLAYER, WORLD, 0.0D, 64.0D, 0.0D, settings, sink);
        assertEquals(List.of(1.0F), sink.of(7), "the first sighting sends the base range");

        for (int i = 0; i < 5; i++) {
            mirror.pass(PLAYER, WORLD, 0.0D, 64.0D, 0.0D, settings, sink);
        }
        assertEquals(1, sink.of(7).size(), "standing still sends nothing more");

        mirror.pass(PLAYER, WORLD, 66.0D, 64.0D, 0.0D, settings, sink);
        assertEquals(1, sink.of(7).size(), "the 8 block margin keeps it shown without a packet");

        mirror.pass(PLAYER, WORLD, 73.0D, 64.0D, 0.0D, settings, sink);
        assertEquals(List.of(1.0F, 0.0F), sink.of(7), "past the margin it is hidden exactly once");

        mirror.pass(PLAYER, WORLD, 73.0D, 64.0D, 0.0D, settings, sink);
        assertEquals(2, sink.of(7).size(), "staying hidden sends nothing");

        mirror.pass(PLAYER, WORLD, 60.0D, 64.0D, 0.0D, settings, sink);
        assertEquals(List.of(1.0F, 0.0F, 1.0F), sink.of(7), "coming back restores the base range once");
    }

    /** A player who changes world or quits carries no answers over, and a rejoin starts from nothing. */
    @Test
    void aWorldChangeAndAQuitBothDropWhatTheViewerWasTold() {
        DisplayViewMirror mirror = new DisplayViewMirror();
        mirror.register(WORLD, 11, FENCE, 0.0D, 64.0D, 0.0D, 1.0F);
        DisplayViewSettings settings = settings(64.0D, 64);
        RecordingSink sink = new RecordingSink();

        mirror.pass(PLAYER, WORLD, 0.0D, 64.0D, 0.0D, settings, sink);
        assertEquals(1, mirror.activeCount(PLAYER));
        assertEquals(1, mirror.stateCount(PLAYER));

        RecordingSink other = new RecordingSink();
        DisplayViewMirror.PassResult moved =
                mirror.pass(PLAYER, OTHER_WORLD, 0.0D, 64.0D, 0.0D, settings, other);
        assertEquals(0, moved.checked(), "the other world holds no display for this viewer");
        assertEquals(0, mirror.activeCount(PLAYER), "the previous world's seen set is dropped");
        assertEquals(0, mirror.stateCount(PLAYER), "and so is what the viewer was told");

        RecordingSink back = new RecordingSink();
        mirror.pass(PLAYER, WORLD, 0.0D, 64.0D, 0.0D, settings, back);
        assertEquals(List.of(1.0F), back.of(11), "a reset viewer is told the range again");

        Set<UUID> online = Set.of(player(1));
        assertEquals(1, mirror.retainOnline(online), "the mirror of a player who is gone is dropped");
        assertEquals(0, mirror.activeCount(PLAYER));
        assertEquals(0, mirror.stateCount(PLAYER));

        RecordingSink rejoin = new RecordingSink();
        mirror.pass(PLAYER, WORLD, 0.0D, 64.0D, 0.0D, settings, rejoin);
        assertEquals(List.of(1.0F), rejoin.of(11), "a rejoin starts with nothing remembered");

        assertTrue(mirror.forget(PLAYER), "an explicit quit drops the mirror");
        assertFalse(mirror.forget(PLAYER), "and dropping it twice is a no-op");
    }

    /** A per-type distance is where that type is hidden, not the global one. */
    @Test
    void aTypeWithItsOwnDistanceIsHiddenAtThatDistance() {
        DisplayViewMirror mirror = new DisplayViewMirror();
        mirror.register(WORLD, 21, FENCE, 0.0D, 64.0D, 0.0D, 1.0F);
        mirror.register(WORLD, 22, GATE, 0.0D, 64.0D, 0.0D, 1.0F);
        DisplayViewSettings settings = new DisplayViewSettings(64.0D, 8.0D, 64, Map.of(
                FENCE, new DisplayViewSettings.Type(true, 128.0D),
                GATE, new DisplayViewSettings.Type(true, 32.0D)));
        RecordingSink sink = new RecordingSink();

        mirror.pass(PLAYER, WORLD, 100.0D, 64.0D, 0.0D, settings, sink);

        assertEquals(List.of(1.0F), sink.of(21), "100 blocks is inside the fence's own 128");
        assertEquals(List.of(0.0F), sink.of(22), "and outside the gate's own 32");
    }

    /** Turning culling off for a type hands back the range it hid, then never looks at it again. */
    @Test
    void aTypeThatTurnsCullingOffIsNeverHiddenAgain() {
        DisplayViewMirror mirror = new DisplayViewMirror();
        mirror.register(WORLD, 31, FENCE, 0.0D, 64.0D, 0.0D, 1.0F);
        DisplayViewSettings off = new DisplayViewSettings(64.0D, 8.0D, 64,
                Map.of(FENCE, new DisplayViewSettings.Type(false, 64.0D)));
        RecordingSink sink = new RecordingSink();

        mirror.pass(PLAYER, WORLD, 70.0D, 64.0D, 0.0D, settings(64.0D, 64), sink);
        assertEquals(List.of(0.0F), sink.of(31), "with culling on, 70 blocks hides the display");

        mirror.pass(PLAYER, WORLD, 70.0D, 64.0D, 0.0D, off, sink);
        assertEquals(List.of(0.0F, 1.0F), sink.of(31), "turning culling off gives the range back once");
        assertEquals(0, mirror.stateCount(PLAYER), "and the viewer is not remembered for that type any more");

        for (int i = 0; i < 3; i++) {
            mirror.pass(PLAYER, WORLD, 70.0D, 64.0D, 0.0D, off, sink);
        }
        assertEquals(2, sink.of(31).size(), "no later pass touches a type that may not be culled");
    }

    /** One culler pass with nothing registered must not create a packet or a viewer entry. */
    @Test
    void anEmptyRecordCostsNothing() {
        DisplayViewMirror mirror = new DisplayViewMirror();
        RecordingSink sink = new RecordingSink();

        DisplayViewMirror.PassResult result =
                mirror.pass(PLAYER, WORLD, 0.0D, 64.0D, 0.0D, settings(64.0D, 64), sink);

        assertEquals(0, result.checked());
        assertEquals(0, result.sent());
        assertEquals(0, sink.count());
    }
}
