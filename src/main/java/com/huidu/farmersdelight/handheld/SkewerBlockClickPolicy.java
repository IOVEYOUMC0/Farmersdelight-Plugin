package com.huidu.farmersdelight.handheld;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Decides whether one right-click may start handheld skewer cooking, as pure logic so every branch is
 * testable without a server.
 *
 * An air click is always ours. A block click is ours when the block cannot do anything with the held item
 * (plain terrain, fire, soul fire, lava and magma blocks all fall through to the item's own use) or when the
 * player is sneaking, which is the deliberate way to cook while aiming at a block that would otherwise take
 * the click. A non-sneaking click on a block that does have an interaction of its own stays with that block:
 * a campfire takes the skewer as food and a stove may too.
 *
 * Whether such a block would still accept the item cannot be answered from Java, so it is not part of the
 * rule: neither Bukkit nor CraftEngine offers a cheap, thread-correct way to see a campfire's four slots or a
 * stove's contents from here, and guessing would either steal vanilla's click or drop ours. Those clicks keep
 * belonging to the block unless the player sneaks.
 *
 * The heat condition is not part of this class either: the caller starts cooking only when the player is
 * inside the three by three by three cube or is on fire, and that check is unchanged.
 */
public final class SkewerBlockClickPolicy {

    /** What the interaction handler should do with this click. */
    public enum Decision {

        /** Right click in the air: always ours to handle. */
        ACCEPT_AIR,
        /** Right click on a block that has no interaction of its own: ours to handle. */
        ACCEPT_BLOCK_WITHOUT_INTERACTION,
        /** Right click on any block while sneaking: the player asked us to take this click. */
        ACCEPT_BLOCK_SNEAKING,
        /** The block claims the click (a campfire taking food), so vanilla or CraftEngine keeps it. */
        DECLINE_BLOCK_HAS_INTERACTION,
        /** Not a right click. */
        DECLINE_NOT_A_RIGHT_CLICK
    }

    private SkewerBlockClickPolicy() {
    }

    /**
     * The acceptance rule. blockHasInteraction is "the clicked block does something with this item
     * before we would" (campfire food placement being the case that matters).
     */
    @ApiStatus.Internal
    public static Decision decide(boolean rightClickAir, boolean rightClickBlock,
                                  boolean blockHasInteraction, boolean sneaking) {
        if (rightClickAir) {
            return Decision.ACCEPT_AIR;
        }
        if (!rightClickBlock) {
            return Decision.DECLINE_NOT_A_RIGHT_CLICK;
        }
        if (sneaking) {
            return Decision.ACCEPT_BLOCK_SNEAKING;
        }
        if (!blockHasInteraction) {
            return Decision.ACCEPT_BLOCK_WITHOUT_INTERACTION;
        }
        return Decision.DECLINE_BLOCK_HAS_INTERACTION;
    }

    /** Whether this decision lets the skewer cooking start. */
    public static boolean accepted(@Nullable Decision decision) {
        return decision == Decision.ACCEPT_AIR
                || decision == Decision.ACCEPT_BLOCK_WITHOUT_INTERACTION
                || decision == Decision.ACCEPT_BLOCK_SNEAKING;
    }

    /**
     * The hands to try, in order. Paper can report a null hand for an air interaction; the handler then has
     * to look at the main hand and, if that is not a skewer, the off hand instead of dropping the click.
     *
     * @return {main} when the reported hand is already known, {main, off} when it is not
     */
    public static int[] handsToCheck(boolean reportedHandKnown) {
        return reportedHandKnown ? new int[]{0} : new int[]{0, 1};
    }
}
