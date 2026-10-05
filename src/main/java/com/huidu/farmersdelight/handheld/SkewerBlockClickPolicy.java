package com.huidu.farmersdelight.handheld;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Decides whether one right-click may start handheld skewer cooking, as pure logic so every branch is
 * testable without a server.
 *
 * <p><b>Why a block click is accepted at all.</b> Farmer's Delight 1.4 only implements
 * {@code HandCookedItem#use} (right click in the air), but NeoForge falls back to {@code use} when the
 * clicked block has no interaction of its own. Accepting only air clicks therefore loses the case a player
 * actually performs next to a heat source: aiming at it. That was a silent failure — the hook returned before
 * any log — so the block click is now accepted, but <b>only</b> when it cannot steal a vanilla/CraftEngine
 * interaction:
 * <ul>
 *   <li>the clicked block has to be a heat source (see {@code api/block/HeatSources}); and</li>
 *   <li>the block must not have its own interaction for the held item. A campfire takes food (placing the
 *       skewer on it) and a stove may too, so those keep owning the click; {@code fire}, {@code soul_fire},
 *       {@code lava} and {@code magma_block} have no interaction and are taken over.</li>
 * </ul>
 * Air clicks stay unconditional: nothing else can claim them.
 *
 * <p>The rest of the semantics are unchanged: the heat source is only checked when cooking starts, cooking
 * takes {@code handheld-skewer.cooking-time-ticks} (120) ticks of holding the use key, one skewer per
 * session, and five interactions cancel it.
 */
public final class SkewerBlockClickPolicy {

    /** What the interaction handler should do with this click. */
    public enum Decision {

        /** Right click in the air: always ours to handle. */
        ACCEPT_AIR,
        /** Right click on a heat source that has no interaction of its own: ours to handle. */
        ACCEPT_BLOCK_HEAT_WITHOUT_INTERACTION,
        /** The block claims the click (a campfire taking food), so vanilla/CraftEngine keeps it. */
        DECLINE_BLOCK_HAS_INTERACTION,
        /** Clicking a block that is not a heat source. */
        DECLINE_BLOCK_NOT_HEAT,
        /** Not a right click, or no block data at all. */
        DECLINE_NOT_A_RIGHT_CLICK
    }

    private SkewerBlockClickPolicy() {
    }

    /**
     * The acceptance rule. {@code blockHasInteraction} is "the clicked block does something with this item
     * before we would" (campfire food placement being the case that matters).
     */
    @ApiStatus.Internal
    public static Decision decide(boolean rightClickAir, boolean rightClickBlock,
                                  boolean blockIsHeatSource, boolean blockHasInteraction) {
        if (rightClickAir) {
            return Decision.ACCEPT_AIR;
        }
        if (!rightClickBlock) {
            return Decision.DECLINE_NOT_A_RIGHT_CLICK;
        }
        if (blockHasInteraction) {
            return Decision.DECLINE_BLOCK_HAS_INTERACTION;
        }
        if (!blockIsHeatSource) {
            return Decision.DECLINE_BLOCK_NOT_HEAT;
        }
        return Decision.ACCEPT_BLOCK_HEAT_WITHOUT_INTERACTION;
    }

    /** Whether this decision lets the skewer cooking start. */
    public static boolean accepted(@Nullable Decision decision) {
        return decision == Decision.ACCEPT_AIR || decision == Decision.ACCEPT_BLOCK_HEAT_WITHOUT_INTERACTION;
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
