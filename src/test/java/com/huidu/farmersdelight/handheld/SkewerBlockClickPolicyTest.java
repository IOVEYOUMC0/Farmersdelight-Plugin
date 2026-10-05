package com.huidu.farmersdelight.handheld;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the block-click acceptance rule: air clicks are ours, a block without an interaction of its own is
 * ours, a sneaking player takes the click from any block, and a non-sneaking click on a block that has an
 * interaction stays with that block. Changing the rule either way has to fail here.
 */
class SkewerBlockClickPolicyTest {

    @Test
    void anAirClickIsAlwaysAccepted() {
        assertEquals(SkewerBlockClickPolicy.Decision.ACCEPT_AIR,
                SkewerBlockClickPolicy.decide(true, false, false, false));
        assertEquals(SkewerBlockClickPolicy.Decision.ACCEPT_AIR,
                SkewerBlockClickPolicy.decide(true, false, true, true),
                "an air click cannot be claimed by a block, so the other flags are irrelevant");
        assertTrue(SkewerBlockClickPolicy.accepted(SkewerBlockClickPolicy.Decision.ACCEPT_AIR));
    }

    @Test
    void aBlockWithoutItsOwnInteractionIsAccepted() {
        // fire / soul_fire / lava / magma_block / plain terrain: they do nothing with the skewer themselves.
        assertEquals(SkewerBlockClickPolicy.Decision.ACCEPT_BLOCK_WITHOUT_INTERACTION,
                SkewerBlockClickPolicy.decide(false, true, false, false));
        assertTrue(SkewerBlockClickPolicy.accepted(
                SkewerBlockClickPolicy.Decision.ACCEPT_BLOCK_WITHOUT_INTERACTION));
    }

    @Test
    void sneakingTakesTheClickFromAnyBlock() {
        // Campfire: it takes food, so the click is only ours when the player asks for it by sneaking.
        assertEquals(SkewerBlockClickPolicy.Decision.ACCEPT_BLOCK_SNEAKING,
                SkewerBlockClickPolicy.decide(false, true, true, true));
        assertTrue(SkewerBlockClickPolicy.accepted(SkewerBlockClickPolicy.Decision.ACCEPT_BLOCK_SNEAKING));
    }

    @Test
    void aCampfireKeepsItsOwnClickWhenNotSneaking() {
        assertEquals(SkewerBlockClickPolicy.Decision.DECLINE_BLOCK_HAS_INTERACTION,
                SkewerBlockClickPolicy.decide(false, true, true, false),
                "not sneaking plus an interaction plus room for the item still belongs to the block");
        assertFalse(SkewerBlockClickPolicy.accepted(
                SkewerBlockClickPolicy.Decision.DECLINE_BLOCK_HAS_INTERACTION));
    }

    @Test
    void anythingThatIsNotARightClickIsDeclined() {
        assertEquals(SkewerBlockClickPolicy.Decision.DECLINE_NOT_A_RIGHT_CLICK,
                SkewerBlockClickPolicy.decide(false, false, true, false),
                "a left click or a physical interaction is never ours");
        assertEquals(SkewerBlockClickPolicy.Decision.DECLINE_NOT_A_RIGHT_CLICK,
                SkewerBlockClickPolicy.decide(false, false, false, true),
                "sneaking does not turn a left click into a right click");
        assertFalse(SkewerBlockClickPolicy.accepted(null));
    }

    @Test
    void anUnknownHandFallsBackToTheOtherHand() {
        assertArrayEquals(new int[]{0}, SkewerBlockClickPolicy.handsToCheck(true),
                "a known hand is the only one to look at");
        assertArrayEquals(new int[]{0, 1}, SkewerBlockClickPolicy.handsToCheck(false),
                "an unknown hand has to try main and off hand instead of dropping the click");
    }
}
