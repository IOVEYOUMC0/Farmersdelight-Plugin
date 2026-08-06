package com.huidu.farmersdelight.api.effect;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.effect.EffectManager;
import com.huidu.farmersdelight.listener.FoodEatListener;
import org.bukkit.entity.Player;

public final class FarmersDelightFoodEffects {

    private FarmersDelightFoodEffects() {
    }

    public static void applyComfort(Player player, int durationSeconds) {
        applyComfort(player, durationSeconds, 1);
    }

    public static void applyComfort(Player player, int durationSeconds, int level) {
        if (player != null && durationSeconds > 0) {
            EffectManager.applyComfort(player, durationSeconds, level);
        }
    }

    public static void applyNourishment(Player player, int durationSeconds) {
        applyNourishment(player, durationSeconds, 1);
    }

    public static void applyNourishment(Player player, int durationSeconds, int level) {
        if (player != null && durationSeconds > 0) {
            EffectManager.applyNourishment(player, durationSeconds, level);
        }
    }

    public static boolean hasComfort(Player player) {
        return player != null && EffectManager.hasComfort(player);
    }

    public static boolean hasNourishment(Player player) {
        return player != null && EffectManager.hasNourishment(player);
    }

    public static void removeComfort(Player player) {
        if (player != null) {
            EffectManager.removeComfort(player);
        }
    }

    public static void removeNourishment(Player player) {
        if (player != null) {
            EffectManager.removeNourishment(player);
        }
    }

    public static void registerComfortFood(String itemId, int durationSeconds) {
        FoodEatListener listener = listener();
        if (listener != null) {
            listener.registerComfortFood(itemId, durationSeconds);
        }
    }

    public static void registerNourishmentFood(String itemId, int durationSeconds) {
        FoodEatListener listener = listener();
        if (listener != null) {
            listener.registerNourishmentFood(itemId, durationSeconds);
        }
    }

    public static void unregisterComfortFood(String itemId) {
        FoodEatListener listener = listener();
        if (listener != null) {
            listener.unregisterComfortFood(itemId);
        }
    }

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
