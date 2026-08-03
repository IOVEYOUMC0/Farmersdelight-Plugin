package com.huidu.farmersdelight.api.effect;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.effect.EffectManager;
import com.huidu.farmersdelight.listener.FoodEatListener;
import org.bukkit.entity.Player;

/**
 * Stable, addon-facing access to FarmersDelight's custom food effects (Comfort = slow regeneration while not
 * saturated; Nourishment = suppressed exhaustion). Addons can apply/query/clear effects directly, or register
 * their own custom food items so eating them triggers an effect.
 *
 * Lives in the name-stable api package; every signature uses only Bukkit / java types. Method bodies
 * delegate to renamed internals.
 *
 * Threading: the apply/remove methods mutate player state and send messages, so they must run on the player's
 * owning thread (the main thread on Paper, the player's region thread on Folia), not from an async task.
 */
public final class FarmersDelightFoodEffects {

    private FarmersDelightFoodEffects() {
    }

    /** Applies (or refreshes, keeping the longer) the Comfort effect for durationSeconds at level 1. */
    public static void applyComfort(Player player, int durationSeconds) {
        applyComfort(player, durationSeconds, 1);
    }

    /** Applies the Comfort effect at level (1-based) for durationSeconds, stacking against any
     *  active dose by the vanilla rule (stronger replaces + refreshes, equal extends, weaker is ignored). */
    public static void applyComfort(Player player, int durationSeconds, int level) {
        if (player != null && durationSeconds > 0) {
            EffectManager.applyComfort(player, durationSeconds, level);
        }
    }

    /** Applies (or refreshes, keeping the longer) the Nourishment effect for durationSeconds at level 1. */
    public static void applyNourishment(Player player, int durationSeconds) {
        applyNourishment(player, durationSeconds, 1);
    }

    /** Applies the Nourishment effect at level (1-based) for durationSeconds, with the same
     *  vanilla stacking rule as int, int). */
    public static void applyNourishment(Player player, int durationSeconds, int level) {
        if (player != null && durationSeconds > 0) {
            EffectManager.applyNourishment(player, durationSeconds, level);
        }
    }

    /** True while player has an active Comfort effect. */
    public static boolean hasComfort(Player player) {
        return player != null && EffectManager.hasComfort(player);
    }

    /** True while player has an active Nourishment effect. */
    public static boolean hasNourishment(Player player) {
        return player != null && EffectManager.hasNourishment(player);
    }

    /** Clears the Comfort effect from player. */
    public static void removeComfort(Player player) {
        if (player != null) {
            EffectManager.removeComfort(player);
        }
    }

    /** Clears the Nourishment effect from player. */
    public static void removeNourishment(Player player) {
        if (player != null) {
            EffectManager.removeNourishment(player);
        }
    }

    /**
     * Registers an addon food item id ("ns:id", CraftEngine custom or minecraft:..) so eating it
     * grants Comfort for durationSeconds. Works regardless of the comfort-foods config toggle and
     * survives /fd reload. No-op when FarmersDelight is unavailable.
     */
    public static void registerComfortFood(String itemId, int durationSeconds) {
        FoodEatListener listener = listener();
        if (listener != null) {
            listener.registerComfortFood(itemId, durationSeconds);
        }
    }

    /**
     * Registers an addon food item id so eating it grants Nourishment for durationSeconds. Works
     * regardless of the nourishment-foods config toggle and survives /fd reload.
     */
    public static void registerNourishmentFood(String itemId, int durationSeconds) {
        FoodEatListener listener = listener();
        if (listener != null) {
            listener.registerNourishmentFood(itemId, durationSeconds);
        }
    }

    /** Removes an addon comfort-food mapping registered via #registerComfortFood. */
    public static void unregisterComfortFood(String itemId) {
        FoodEatListener listener = listener();
        if (listener != null) {
            listener.unregisterComfortFood(itemId);
        }
    }

    /** Removes an addon nourishment-food mapping registered via #registerNourishmentFood. */
    public static void unregisterNourishmentFood(String itemId) {
        FoodEatListener listener = listener();
        if (listener != null) {
            listener.unregisterNourishmentFood(itemId);
        }
    }

    private static FoodEatListener listener() {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        return plugin == null ? null : plugin.getFoodEatListener();
    }
}
