package com.huidu.farmersdelight.block.behavior;

@Deprecated()
public final class StoveCookingBlockEntity {

    private StoveCookingBlockEntity() {
    }

    // Removed the legacy findCampfireRecipe(ItemStack) method and its per-Material LinkedHashMap cache:
    // lookups are handled at runtime by StoveManager.findCampfireRecipe (delegating to CampfireRecipeCache),
    // and this class's version had no live callers left.

    public static void clearRecipeCache() {
        // No-op: the cache has moved to StoveManager's CampfireRecipeCache.
    }
}
