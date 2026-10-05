package com.huidu.farmersdelight.handheld;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * The session bookkeeping behind handheld skewer cooking: a player starts cooking one skewer, the session
 * counts down 120 ticks (6 seconds, the upstream constant), and the skewer is consumed one at a time when it
 * finishes.
 *
 * Everything that touches the world is injected, so the timing rules are testable offline:
 *   - tryStart(UUID) starts a session; it refuses when disabled or when the player already has
 *       one (a player cooks one item at a time — that is also the mutual exclusion against every other
 *       handheld cooking path, the skillet included);
 *   - tick(UUID) counts one tick down; the tick that reaches zero consumes one skewer and
 *       clears the session. A tick without a session does nothing at all (no conversion, no state);
 *   - cancel(UUID) drops the session with zero progress — releasing the use key, switching slots
 *       or hands, dropping the item, quitting, dying or being hit all call it.
 *
 * The conversion itself (onCooked) is injected by the caller. The product comes from this
 * mechanism's own SkewerResultTable mapping (config handheld-skewer.results), not from the
 * pack's campfire recipe: CraftEngine only exposes its recipe queries through NMS-typed bindings, so handheld
 * cooking keeps its own table and the campfire/furnace/smoker paths stay untouched. The service never copies an
 * ItemStack: the caller consumes the held stack in place.
 */
public final class HandCookedSkewerService {

    /** The upstream cooking time: 6 seconds. */
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
