package com.huidu.farmersdelight.block.behavior;

/**
 * 旧版炉灶辅助类。原本位于此处的基于实例的状态模型和 tick 循环已被移除：
 * 运行时的炉灶逻辑现在完全由
 * com.huidu.farmersdelight.manager.StoveManager 负责（它维护着自己的
 * com.huidu.farmersdelight.util.CampfireRecipeCache）。仅保留了静态的
 * clearRecipeCache() 钩子，因为重载/禁用流程仍会引用它。
 */
@Deprecated(forRemoval = false)
public final class StoveCookingBlockEntity {

    private StoveCookingBlockEntity() {
    }

    // 已删除遗留方法 findCampfireRecipe(ItemStack) 及其 per-Material LinkedHashMap 缓存：
    // 运行时由 StoveManager.findCampfireRecipe（委托给 CampfireRecipeCache）负责查找，
    // 本类的版本已无任何活调用方。

    /**
     * 保留此空实现是为了兼容重载/禁用流程（StoveCookingBlockBehavior.clearRecipeCache 仍会调用它）。
     * 由于配方缓存已迁移到 StoveManager，这里不再需要清理任何状态。
     */
    public static void clearRecipeCache() {
        // 无操作：缓存已迁移至 StoveManager 的 CampfireRecipeCache。
    }
}
