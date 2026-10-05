package com.huidu.farmersdelight.visual;

import org.jetbrains.annotations.ApiStatus;

import java.util.Map;

/**
 * The thresholds one culling pass runs with: the fallback view distance, the hysteresis band, how many
 * displays a single player may examine per pass, and the per-type overrides an operator can add.
 *
 *
 * A type is named by the block id of the display, which for the carrier restores is the vanilla carrier
 * whose model the display draws. A type that has no entry, or whose entry leaves its distance out, falls
 * back to the fallback distance. A type whose culling is turned off is never hidden: a pass only hands
 * back a range it had taken away earlier, then stops looking at that type.
 */
@ApiStatus.Internal
public final class DisplayViewSettings {

    /**
     * Upper bound on a configured distance. Vanilla entity tracking, not this pass, decides whether a client
     * is still sent the entity at all, and it stops well below this, so a larger number would only widen the
     * chunk window a pass walks without ever hiding anything earlier.
     */
    public static final double MAX_VIEW_DISTANCE = 192.0D;

    /** One type's rule: whether a pass may hide it, and the distance at which it does. */
    public record Type(boolean culling, double viewDistance) {
    }

    private final double fallbackViewDistance;
    private final double hysteresis;
    private final int checksPerPlayer;
    private final Map<String, Type> types;
    private final double windowRadius;

    public DisplayViewSettings(double fallbackViewDistance, double hysteresis, int checksPerPlayer,
                               Map<String, Type> types) {
        this.fallbackViewDistance = clamp(fallbackViewDistance);
        this.hysteresis = Math.max(0.0D, hysteresis);
        this.checksPerPlayer = Math.max(1, checksPerPlayer);
        this.types = types == null || types.isEmpty() ? Map.of() : Map.copyOf(types);
        double widest = this.fallbackViewDistance;
        for (Type type : this.types.values()) {
            if (type.culling()) {
                widest = Math.max(widest, clamp(type.viewDistance()));
            }
        }
        this.windowRadius = widest + this.hysteresis;
    }

    public static DisplayViewSettings defaults() {
        return new DisplayViewSettings(64.0D, 8.0D, 64, Map.of());
    }

    public double fallbackViewDistance() {
        return this.fallbackViewDistance;
    }

    public double hysteresis() {
        return this.hysteresis;
    }

    public int checksPerPlayer() {
        return this.checksPerPlayer;
    }

    /** False only for a type that is listed and turns its own culling off. */
    public boolean cullingEnabled(String typeKey) {
        Type type = this.types.get(typeKey);
        return type == null || type.culling();
    }

    /** The type's own distance, or the fallback one when the type has no entry or lists none. */
    public double viewDistanceFor(String typeKey) {
        Type type = this.types.get(typeKey);
        return type == null ? this.fallbackViewDistance : clamp(type.viewDistance());
    }

    /**
     * How far a pass looks for displays it has not seen yet. It has to reach the widest distance any type is
     * hidden at plus the hysteresis band, or a display of that type would never be found again after the
     * viewer left the window.
     */
    public double windowRadius() {
        return this.windowRadius;
    }

    private static double clamp(double distance) {
        if (!(distance > 0.0D)) {
            return 0.0D;
        }
        return Math.min(distance, MAX_VIEW_DISTANCE);
    }
}
