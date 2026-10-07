package com.huidu.farmersdelight.api.gui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The click-take arithmetic behind the container GUIs, asserted as plain numbers: the branches the containers
 * share, the three answers they deliberately differ on, and the invariant that a take never exceeds the slot
 * or the room the caller allows. The cooking pot answers through it now, and only through its take decision:
 * where the taken items land is still the pot's own business.
 */
class GuiTakeAmountsTest {

    private static final Path POT_TAKER = Path.of("src", "main", "java", "com", "huidu", "farmersdelight",
            "gui", "CookingPotOutputTaker.java");

    @Test
    void aShiftClickAndADropAllTakeTheWholeSlot() {
        assertEquals(64, GuiTakeAmounts.take(GuiTakeAmounts.Click.SHIFT, 64, 0, true, false, 16));
        assertEquals(64, GuiTakeAmounts.take(GuiTakeAmounts.Click.DROP_ALL, 64, 0, true, false, 16));
        assertEquals(3, GuiTakeAmounts.take(GuiTakeAmounts.Click.SHIFT, 3, 5, false, true, 16));
    }

    @Test
    void aDropOneTakesExactlyOneItem() {
        assertEquals(1, GuiTakeAmounts.take(GuiTakeAmounts.Click.DROP_ONE, 64, 0, true, false, 16));
        assertEquals(0, GuiTakeAmounts.take(GuiTakeAmounts.Click.DROP_ONE, 0, 0, true, false, 16));
    }

    @Test
    void anEmptySlotNeverGivesAnything() {
        for (GuiTakeAmounts.Click click : GuiTakeAmounts.Click.values()) {
            assertEquals(0, GuiTakeAmounts.take(click, 0, 0, true, false, 64), click + " on an empty slot");
        }
    }

    @Test
    void anEmptyCursorTakesAsMuchAsTheCallerAccepts() {
        assertEquals(5, GuiTakeAmounts.take(GuiTakeAmounts.Click.LEFT, 5, 0, true, false, 16));
        assertEquals(1, GuiTakeAmounts.take(GuiTakeAmounts.Click.RIGHT, 5, 0, true, false, 16));
        assertEquals(0, GuiTakeAmounts.take(GuiTakeAmounts.Click.LEFT, 5, 0, true, false, 0));
    }

    @Test
    void aCursorHoldingSomethingElseTakesNothing() {
        assertEquals(0, GuiTakeAmounts.take(GuiTakeAmounts.Click.LEFT, 64, 4, false, false, 64));
        assertEquals(0, GuiTakeAmounts.take(GuiTakeAmounts.Click.RIGHT, 64, 4, false, false, 64));
    }

    @Test
    void aSimilarCursorTakesOnlyTheRoomThatIsLeft() {
        assertEquals(12, GuiTakeAmounts.take(GuiTakeAmounts.Click.LEFT, 64, 4, false, true, 16));
        assertEquals(1, GuiTakeAmounts.take(GuiTakeAmounts.Click.RIGHT, 64, 15, false, true, 16));
        assertEquals(0, GuiTakeAmounts.take(GuiTakeAmounts.Click.LEFT, 64, 16, false, true, 16));
        assertEquals(0, GuiTakeAmounts.take(GuiTakeAmounts.Click.LEFT, 64, 20, false, true, 16));
        assertEquals(16, GuiTakeAmounts.take(GuiTakeAmounts.Click.LEFT, 64, 0, false, true, 16));
    }

    @Test
    void aNegativeCursorAmountCountsAsAnEmptyHand() {
        assertEquals(16, GuiTakeAmounts.take(GuiTakeAmounts.Click.LEFT, 64, -3, false, true, 16));
    }

    @Test
    void theThreeContainersKeepTheirOwnAnswers() {
        // The cooking pot passes the smaller of the item's stack size and the container's, so a full slot
        // still gives only what one container row can hold.
        assertEquals(16, GuiTakeAmounts.take(GuiTakeAmounts.Click.LEFT, 64, 0, true, false, 16));
        // The keg passes the item's own stack size, so the same click there takes the whole slot.
        assertEquals(64, GuiTakeAmounts.take(GuiTakeAmounts.Click.LEFT, 64, 0, true, false, 64));
        // The crab trap passes the smaller of the cursor and the container, and collects into a cursor that
        // already holds part of the stack.
        assertEquals(12, GuiTakeAmounts.take(GuiTakeAmounts.Click.LEFT, 64, 4, false, true, 16));
        // A drop key is the keg's own branch: one item for Q, the whole stack for control plus Q.
        assertEquals(1, GuiTakeAmounts.take(GuiTakeAmounts.Click.DROP_ONE, 64, 0, true, false, 64));
        assertEquals(64, GuiTakeAmounts.take(GuiTakeAmounts.Click.DROP_ALL, 64, 0, true, false, 64));
    }

    @Test
    void aTakeNeverExceedsTheSlotOrTheRoomOrGoesNegative() {
        for (GuiTakeAmounts.Click click : GuiTakeAmounts.Click.values()) {
            for (int stored : new int[]{0, 1, 5, 64}) {
                for (int cursor : new int[]{0, 4, 16, 64}) {
                    for (int limit : new int[]{0, 1, 8, 16, 64}) {
                        for (boolean cursorEmpty : new boolean[]{true, false}) {
                            for (boolean similar : new boolean[]{true, false}) {
                                int taken = GuiTakeAmounts.take(click, stored, cursor, cursorEmpty, similar, limit);
                                String where = click + " stored=" + stored + " cursor=" + cursor + " empty="
                                        + cursorEmpty + " similar=" + similar + " limit=" + limit;
                                assertTrue(taken >= 0, "negative take: " + where);
                                assertTrue(taken <= stored, "took more than the slot holds: " + where);
                                if (taken > 0 && (click == GuiTakeAmounts.Click.LEFT
                                        || click == GuiTakeAmounts.Click.RIGHT)) {
                                    int room = cursorEmpty ? limit : limit - cursor;
                                    assertTrue(taken <= Math.max(0, room), "took more than the room: " + where);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void theCookingPotResolvesItsTakeThroughTheSharedRule() throws IOException {
        String taker = read(POT_TAKER);

        assertTrue(taker.contains("GuiTakeAmounts.take(click, stored, cursorAmount, cursorEmpty, cursorSimilar, limit)"),
                "the pot's decision is the shared arithmetic now");
        assertTrue(taker.contains("currentOutput.getMaxStackSize(), containerMaxStack)"),
                "and the limit stays the smaller of the item's own stack size and the container's");

        // "How much does this click take" and "where does it land" are separate decisions: the first one is the
        // shared rule now, the second never changed, and the cursor's own maximum is only still read by the second.
        int take = taker.indexOf("resolveTake(boolean shiftClick");
        int delivery = taker.indexOf("deliverOutputToPlayer(");
        assertTrue(take > 0 && delivery > take, "both decisions are still in the file");
        assertFalse(taker.substring(take, delivery).contains("cursor.getMaxStackSize()"),
                "the take path no longer clamps with the cursor's maximum; similar stacks share one maximum");
        assertTrue(taker.substring(delivery).contains("Math.min(cursor.getMaxStackSize(),"
                        + " event.getView().getTopInventory().getMaxStackSize())"),
                "the delivery path is untouched, so where the items land did not change");
    }

    private static String read(Path path) throws IOException {
        Path resolved = path;
        if (!Files.isRegularFile(resolved)) {
            Path nested = Path.of("FarmersDelight").resolve(path);
            if (Files.isRegularFile(nested)) {
                resolved = nested;
            }
        }
        assertTrue(Files.isRegularFile(resolved), "missing source file: " + path + " (working directory "
                + Path.of("").toAbsolutePath() + ")");
        return Files.readString(resolved).replace("\r\n", "\n").replaceAll("\\s+", " ");
    }
}
