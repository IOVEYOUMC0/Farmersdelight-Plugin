package com.huidu.farmersdelight.api.recipe;

import com.huidu.farmersdelight.api.PluginAccess;
import com.huidu.farmersdelight.FarmersDelightPlugin;
// Used only inside this class' own helpers, never in a public signature; the public TYPE_* values above are
// literals so the facade does not re-export an internal type.
import com.huidu.farmersdelight.recipe.RecipeDiscoveryManager;
import org.bukkit.entity.Player;

import java.util.Set;

public final class FarmersDelightRecipeDiscovery {

    // Literals on purpose rather than aliases of RecipeDiscoveryManager's constants: these are public api
    // values, and re-exporting them from an internal class would put that class on the facade's compile-time
    // surface (the api boundary check flags exactly that). RecipeDiscoveryManager keeps its own copies.
    public static final String TYPE_COOKING_POT = "farmersdelight:cooking_pot";
    public static final String TYPE_CUTTING_BOARD = "farmersdelight:cutting_board";

    private FarmersDelightRecipeDiscovery() {
    }

    public static boolean isEnabled() {
        RecipeDiscoveryManager manager = manager();
        return manager != null && manager.isEnabled();
    }

    public static boolean isUnlocked(Player player, String typeId, String recipeId) {
        RecipeDiscoveryManager manager = manager();
        return manager == null || player == null
                || manager.isUnlocked(player.getUniqueId(), typeId, recipeId);
    }

    public static boolean unlock(Player player, String typeId, String recipeId) {
        RecipeDiscoveryManager manager = manager();
        return manager != null && player != null
                && manager.unlock(player.getUniqueId(), typeId, recipeId);
    }

    public static void lock(Player player, String typeId, String recipeId) {
        RecipeDiscoveryManager manager = manager();
        if (manager != null && player != null) {
            manager.lock(player.getUniqueId(), typeId, recipeId);
        }
    }

    public static int unlockAll(Player player) {
        RecipeDiscoveryManager manager = manager();
        return manager != null && player != null ? manager.unlockAll(player.getUniqueId()) : 0;
    }

    public static Set<String> unlockedOf(Player player, String typeId) {
        RecipeDiscoveryManager manager = manager();
        return manager != null && player != null
                ? manager.unlockedOf(player.getUniqueId(), typeId)
                : Set.of();
    }

    public static void triggerObtain(Player player, String itemId) {
        RecipeDiscoveryManager manager = manager();
        if (manager != null && player != null && itemId != null) {
            // Reuse the obtain pathway by id (the manager resolves the recipe index itself).
            manager.onObtain(player, itemId);
        }
    }

    private static RecipeDiscoveryManager manager() {
        FarmersDelightPlugin plugin = PluginAccess.pluginOrNull();
        return plugin == null ? null : plugin.getRecipeDiscoveryManager();
    }
}
