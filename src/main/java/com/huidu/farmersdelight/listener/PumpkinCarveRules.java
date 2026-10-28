package com.huidu.farmersdelight.listener;

import org.bukkit.block.BlockFace;

/**
 * Pure rules for carving a pumpkin with a knife. Kept separate from the listener so the facing choice, which is
 * the only arithmetic here, stays unit-testable without a server.
 */
final class PumpkinCarveRules {

    private PumpkinCarveRules() {
    }

    /**
     * The direction a carved pumpkin faces: the clicked side for a horizontal click, and the opposite of the
     * player's facing when the click is on the top or bottom. That matches the mod, where carving the top of a
     * pumpkin produces a face looking back at the player.
     */
    static BlockFace carvedFacing(BlockFace clickedFace, BlockFace playerFacing) {
        if (clickedFace == null) {
            return playerFacing == null ? BlockFace.NORTH : playerFacing.getOppositeFace();
        }
        if (clickedFace == BlockFace.UP || clickedFace == BlockFace.DOWN) {
            return playerFacing == null ? BlockFace.NORTH : playerFacing.getOppositeFace();
        }
        return clickedFace;
    }
}
