package com.huidu.farmersdelight.handheld;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the block-click acceptance rule: air clicks are ours, a heat source without its own interaction is
 * ours, and a campfire that takes food keeps the click. Changing the rule either way has to fail here.
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
    void aHeatSourceWithoutItsOwnInteractionIsAccepted() {
        // fire / soul_fire / lava / magma_block: hot, but they do nothing with the skewer themselves.
        assertEquals(SkewerBlockClickPolicy.Decision.ACCEPT_BLOCK_HEAT_WITHOUT_INTERACTION,
                SkewerBlockClickPolicy.decide(false, true, true, false));
        assertTrue(SkewerBlockClickPolicy.accepted(
                SkewerBlockClickPolicy.Decision.ACCEPT_BLOCK_HEAT_WITHOUT_INTERACTION));
    }

    @Test
    void aCampfireKeepsItsOwnClick() {
        // A campfire accepts food, so the skewer must not be cooked behind vanilla's back.
        assertEquals(SkewerBlockClickPolicy.Decision.DECLINE_BLOCK_HAS_INTERACTION,
                SkewerBlockClickPolicy.decide(false, true, true, true));
        assertFalse(SkewerBlockClickPolicy.accepted(
                SkewerBlockClickPolicy.Decision.DECLINE_BLOCK_HAS_INTERACTION));
    }

    @Test
    void aBlockThatIsNotAHeatSourceIsDeclined() {
        assertEquals(SkewerBlockClickPolicy.Decision.DECLINE_BLOCK_NOT_HEAT,
                SkewerBlockClickPolicy.decide(false, true, false, false));
        assertFalse(SkewerBlockClickPolicy.accepted(SkewerBlockClickPolicy.Decision.DECLINE_BLOCK_NOT_HEAT));
    }

    @Test
    void anythingThatIsNotARightClickIsDeclined() {
        assertEquals(SkewerBlockClickPolicy.Decision.DECLINE_NOT_A_RIGHT_CLICK,
                SkewerBlockClickPolicy.decide(false, false, true, false),
                "a left click or a physical interaction is never ours");
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
