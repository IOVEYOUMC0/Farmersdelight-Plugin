package com.huidu.farmersdelight.manager;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The handheld rewrite must never reach beyond the player's own inventory slot.
 *
 *
 * The live regression this pins: an earlier version also mapped open-container id 0 onto the hotbar
 * (slot + 36), so a handler that outlived its session could rewrite the held item inside a GUI — the
 * enchanting table's option clicks were the visible casualty.
 */
class HandheldCookingDisplayTest {

    @Test
    void bothIdsOfThePlayersOwnInventoryAreRewritten() {
        assertEquals(3, HandheldCookingDisplay.containerSlot(-2, 3), "player-inventory packet: the held slot");
        assertEquals(40, HandheldCookingDisplay.containerSlot(-2, 40), "and the offhand slot");
        // The player's inventory menu is container 0: hotbar slots are 36..44 and the offhand is 45. Skipping it
        // left the client with the server's real item next to the cooking copy, which flipped the model and made
        // the durability bar flicker (the reported regression).
        assertEquals(36, HandheldCookingDisplay.containerSlot(0, 0), "the hotbar slot inside the inventory menu");
        assertEquals(39, HandheldCookingDisplay.containerSlot(0, 3));
        assertEquals(45, HandheldCookingDisplay.containerSlot(0, 40), "and its offhand slot");
        assertEquals(-1, HandheldCookingDisplay.containerSlot(7, 3), "any other container is not ours");
        assertEquals(-1, HandheldCookingDisplay.containerSlot(-1, 3), "the cursor is never a hand");
    }

    @Test
    void aClosedHandleRewritesNothing() {
        assertTrue(HandheldCookingDisplay.rewrites(false, true), "open with a copy: rewrites");
        assertFalse(HandheldCookingDisplay.rewrites(true, true), "closed: the handler has to be inert");
        assertFalse(HandheldCookingDisplay.rewrites(false, false), "no copy: nothing to rewrite");
        assertFalse(HandheldCookingDisplay.rewrites(true, false));
    }
}
