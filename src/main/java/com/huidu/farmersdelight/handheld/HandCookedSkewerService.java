package com.huidu.farmersdelight.handheld;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * Tracks one handheld skewer session per player.
 * Starting requires an enabled service and no existing session; ticks advance the configured cooking time.
 * Completion invokes the injected conversion callback once and clears the session.
 * Cancellation clears progress without consuming items. World access and result resolution belong to the caller.
 */
public final class HandCookedSkewerService {

    /** Default cooking time: six seconds. */
    public static final int COOKING_TICKS = 120;

    private final Map<UUID, Integer> remaining = new ConcurrentHashMap<>();
    private final IntSupplier cookingTicks;
    private final Consumer<UUID> onCooked;
    private volatile boolean enabled;

    public HandCookedSkewerService(boolean enabled, @Nullable IntSupplier cookingTicks,
                                   @Nullable Consumer<UUID> onCooked) {
        this.enabled = enabled;
        this.cookingTicks = cookingTicks == null ? () -> COOKING_TICKS : cookingTicks;
        this.onCooked = onCooked == null ? player -> {
        } : onCooked;
    }

    /** Starts a session unless this path is disabled or the player is already cooking something. */
    public boolean tryStart(UUID player) {
        if (!enabled || player == null) {
            return false;
        }
        return remaining.putIfAbsent(player, Math.max(1, cookingTicks.getAsInt())) == null;
    }

    /**
     * Advances the session by one tick. Returns true when this tick finished the skewer (and therefore ran
     * the conversion); false when the session is still running or the player has none.
     */
    public boolean tick(UUID player) {
        if (player == null) {
            return false;
        }
        Integer left = remaining.get(player);
        if (left == null) {
            return false;
        }
        if (left <= 1) {
            // Clear the session first: a conversion that throws must not leave a stuck session behind.
            remaining.remove(player);
            onCooked.accept(player);
            return true;
        }
        remaining.put(player, left - 1);
        return false;
    }

    /** Drops the session with zero progress; true when there was one. */
    public boolean cancel(@Nullable UUID player) {
        return player != null && remaining.remove(player) != null;
    }

    public boolean isCooking(@Nullable UUID player) {
        return player != null && remaining.containsKey(player);
    }

    /** The ticks left, or -1 when the player is not cooking; used by the tests and a future display. */
    @ApiStatus.Internal
    public int remainingTicks(@Nullable UUID player) {
        Integer left = player == null ? null : remaining.get(player);
        return left == null ? -1 : left;
    }

    public boolean enabled() {
        return enabled;
    }

    /** Applies the config switch; turning it off also cancels the sessions that are already running. */
    public void setEnabled(boolean value) {
        this.enabled = value;
        if (!value) {
            remaining.clear();
        }
    }
}
