package com.huidu.farmersdelight.api.recipe;

import com.huidu.farmersdelight.FarmersDelightPlugin;
import com.huidu.farmersdelight.recipe.RecipeDiscoveryManager;
import org.bukkit.entity.Player;

import java.util.Set;

/**
 * Stable, addon-facing access to the recipe books' per-player discovery (lock/unlock) state. When discovery
 * is disabled (the default), every recipe reads as unlocked and these calls are harmless no-ops.
 *
 * Recipes are addressed by (typeId, recipeId): use TYPE_COOKING_POT /
 * TYPE_CUTTING_BOARD for FarmersDelight's own recipes, or an addon's RecipeType.id() together
 * with a ViewableRecipe.id(). Locking affects only the books' display, never crafting at a station.
 *
 * Lives in the name-stable api package; uses only Bukkit / java types.
 */
public final class FarmersDelightRecipeDiscovery {

    /** Synthetic type id for FarmersDelight cooking-pot recipes. */
    public static final String TYPE_COOKING_POT = RecipeDiscoveryManager.TYPE_COOKING_POT;
    /** Synthetic type id for FarmersDelight cutting-board recipes. */
    public static final String TYPE_CUTTING_BOARD = RecipeDiscoveryManager.TYPE_CUTTING_BOARD;

    private FarmersDelightRecipeDiscovery() {
    }

    /** True when recipe discovery is enabled in the config (recipes start locked). */
    public static boolean isEnabled() {
        RecipeDiscoveryManager manager = manager();
        return manager != null && manager.isEnabled();
    }

    /** True if player has unlocked this recipe (always true while discovery is disabled). */
    public static boolean isUnlocked(Player player, String typeId, String recipeId) {
        RecipeDiscoveryManager manager = manager();
        return manager == null || player == null
                || manager.isUnlocked(player.getUniqueId(), typeId, recipeId);
    }

    /** Unlocks a recipe for player. Returns true if it was newly unlocked. */
    public static boolean unlock(Player player, String typeId, String recipeId) {
        RecipeDiscoveryManager manager = manager();
        return manager != null && player != null
                && manager.unlock(player.getUniqueId(), typeId, recipeId);
    }

    /** Re-locks a recipe for player. */
    public static void lock(Player player, String typeId, String recipeId) {
        RecipeDiscoveryManager manager = manager();
        if (manager != null && player != null) {
            manager.lock(player.getUniqueId(), typeId, recipeId);
        }
    }

    /** Unlocks every known recipe (FarmersDelight + addon) for player; returns how many were new. */
    public static int unlockAll(Player player) {
        RecipeDiscoveryManager manager = manager();
        return manager != null && player != null ? manager.unlockAll(player.getUniqueId()) : 0;
    }

    /** The recipe ids of typeId that player has unlocked. */
    public static Set<String> unlockedOf(Player player, String typeId) {
        RecipeDiscoveryManager manager = manager();
        return manager != null && player != null
                ? manager.unlockedOf(player.getUniqueId(), typeId)
                : Set.of();
    }

    /**
     * Treats itemId as just obtained by player, unlocking any recipe keyed to it (its result
     * or an exact ingredient) when the obtain trigger is enabled. Use for custom "you got this" flows.
     */
    public static void triggerObtain(Player player, String itemId) {
        RecipeDiscoveryManager manager = manager();
        if (manager != null && player != null && itemId != null) {
            // Reuse the obtain pathway by id (the manager resolves the recipe index itself).
            manager.onObtain(player, itemId);
        }
    }

    private static RecipeDiscoveryManager manager() {
        FarmersDelightPlugin plugin = FarmersDelightPlugin.getInstance();
        return plugin == null ? null : plugin.getRecipeDiscoveryManager();
    }
}
