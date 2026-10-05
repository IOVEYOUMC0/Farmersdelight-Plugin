package com.huidu.farmersdelight.handheld;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Reads whether a player is standing next to a heat source, without ever reading a block from a thread that
 * does not own its region.
 *
 * Semantics (upstream parity). Farmer's Delight 1.4 checks this once, when the player starts using
 * the skewer (HandCookedItem#use), and looks at the 3x3x3 block cube around the player (plus "the
 * player is on fire"). It is not re-checked while the skewer cooks, which is what keeps this cheap and
 * region-safe: one dispatch at most per use attempt, and none at all in the common case.
 *
 * Region contract. The caller supplies a RegionAccess; for every candidate position the
 * probe either reads it inline — only when RegionAccess#owns(int, int, int) says this thread owns
 * that block — or hands exactly one task to the owning region. A player who is more than one block away from
 * a region boundary is fully inside one region, so every candidate is owned and no dispatch happens;
 * only a player at the very edge of a region (or standing across a boundary) causes dispatches, one per
 * foreign column (all three heights of a column share one dispatch, because the seam is
 * column-granular). A foreign position is never read inline.
 */
public final class SkewerHeatProbe {

    /** The region seam: implemented over the scheduler in production, faked in tests. */
    public interface RegionAccess {

        /** Whether the calling thread owns the region that contains this block column. */
        boolean owns(int blockX, int blockZ);

        /** Reads the block on the owning thread; only called for owned positions. */
        boolean isHeatSource(int blockX, int blockY, int blockZ);

        /** Runs the read for a position the caller does not own, on the owning region. */
        void runAt(int blockX, int blockZ, Runnable read);

        /** Whether the thread is currently on fire; no block read needed for that case. */
        boolean isOnFire();
    }

    private SkewerHeatProbe() {
    }

    /**
     * True when the player is on fire or any block in the 3x3x3 cube around (x, y, z) is a heat source.
     * Reads only owned positions inline and dispatches every other one exactly once.
     */
    public static boolean isNearHeatSource(@Nullable RegionAccess access, int x, int y, int z) {
        if (access == null) {
            return false;
        }
        if (access.isOnFire()) {
            return true;
        }
        boolean[] found = {false};
        for (int dx = -1; dx <= 1 && !found[0]; dx++) {
            for (int dz = -1; dz <= 1 && !found[0]; dz++) {
                int bx = x + dx;
                int bz = z + dz;
                if (access.owns(bx, bz)) {
                    // Owned column: read its three heights right here, no dispatch.
                    for (int dy = -1; dy <= 1 && !found[0]; dy++) {
                        if (access.isHeatSource(bx, y + dy, bz)) {
                            found[0] = true;
                        }
                    }
                } else {
                    // Foreign region: never read it from this thread, hand the whole column to its owner.
                    // The seam is column-granular (owns/runAt take blockX and blockZ), so one column is one
                    // dispatch, not one per height. The dispatched read only writes into the shared flag.
                    access.runAt(bx, bz, () -> {
                        for (int dy = -1; dy <= 1; dy++) {
                            if (access.isHeatSource(bx, y + dy, bz)) {
                                found[0] = true;
                            }
                        }
                    });
                }
            }
        }
        return found[0];
    }
}
