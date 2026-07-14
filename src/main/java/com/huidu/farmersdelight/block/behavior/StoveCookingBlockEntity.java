package com.huidu.farmersdelight.block.behavior;

/**
 * Legacy stove helper. The instance-based state model and tick loop that once lived here have been removed:
 * runtime stove logic is now handled entirely by
 * com.huidu.farmersdelight.manager.StoveManager (which maintains its own
 * com.huidu.farmersdelight.util.CampfireRecipeCache). Only the static
 * clearRecipeCache() hook is kept, since the reload/disable flow still references it.
 */
@Deprecated(forRemoval = false)
public final class StoveCookingBlockEntity {

    private StoveCookingBlockEntity() {
    }

    // Removed the legacy findCampfireRecipe(ItemStack) method and its per-Material LinkedHashMap cache:
    // lookups are handled at runtime by StoveManager.findCampfireRecipe (delegating to CampfireRecipeCache),
    // and this class's version had no live callers left.

    /**
     * This empty implementation is kept for compatibility with the reload/disable flow (StoveCookingBlockBehavior.clearRecipeCache still calls it).
     * Since the recipe cache has moved to StoveManager, there is no state to clear here anymore.
     */
    public static void clearRecipeCache() {
        // No-op: the cache has moved to StoveManager's CampfireRecipeCache.
    }
}
