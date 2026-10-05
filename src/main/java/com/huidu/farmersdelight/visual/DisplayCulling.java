package com.huidu.farmersdelight.visual;

import com.huidu.farmersdelight.util.scheduler.PluginTask;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * The visibility policy shared by both display paths, kept free of Bukkit types so it can be unit tested.
 *
 *
 * CraftEngine's rule is the reference: a display that a player can no longer see keeps its entity, it just
 * gets a ViewRange of 0 for that player (CullableHolder: "culled", ItemDisplayBlockEntityElement.setCulled),
 * and the configured range comes back when the player returns. Removing and respawning the entity instead
 * would change its entity id, so nothing here ever asks for a removal.
 */
@ApiStatus.Internal
public final class DisplayCulling {

    /** The range a culled viewer is told to use: invisible, entity untouched. */
    public static final float CULLED_RANGE = 0.0F;

    private DisplayCulling() {
    }

    /** The ViewRange to send a viewer: its configured range, or 0 while it must not see the display. */
    public static float rangeFor(boolean visible, float baseRange) {
        if (!visible) {
            return CULLED_RANGE;
        }
        return Math.max(0.0F, baseRange);
    }

    /**
     * Squared-distance test in blocks. {@code extra} is the hysteresis: an entity already shown to this
     * viewer stays shown for that many extra blocks, so a player standing exactly on the boundary does not
     * flap between culled and shown on every pass.
     */
    public static boolean isVisible(double distanceSquared, double viewDistance, double extra) {
        double limit = Math.max(0.0D, viewDistance) + Math.max(0.0D, extra);
        return distanceSquared <= limit * limit;
    }

    /**
     * Whether the culler's repeating task has to be (re)created. A task that was cancelled — a scheduler
     * shutdown, a plugin disable followed by an enable that handed back a cached culler — must not be mistaken
     * for a live one: that is the silent failure this guards against (no exception, no log, culling just
     * stops). Keep this rule here so it can be unit tested without a server.
     */
    public static boolean needsFreshTask(@Nullable PluginTask task) {
        return task == null || task.isCancelled();
    }

    /**
     * Remembers what one viewer was last told about one display, so a pass only produces a packet when the
     * answer changed. One instance per (entity, player).
     */
    public static final class ViewRangeState {

        private float lastSent = Float.NaN;

        /** The range to send, or NaN when this viewer already has the right value. */
        public float update(boolean visible, float baseRange) {
            float next = rangeFor(visible, baseRange);
            if (next == this.lastSent) {
                return Float.NaN;
            }
            this.lastSent = next;
            return next;
        }

        /** What this viewer was last told, or NaN before the first update. */
        public float lastSent() {
            return this.lastSent;
        }

        public boolean hasSent() {
            return !Float.isNaN(this.lastSent);
        }
    }
}
