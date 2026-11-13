package com.huidu.farmersdelight.listener;

/**
 * Decision rules for the Paper-side equivalent of the upstream Nourishment always-eat mixin: a player under
 * Nourishment may keep eating at full hunger. Paper has no canEat hook, so the listener briefly lowers the food
 * level by one to let the vanilla consume start and restores it on the next tick. The rules stay pure so the
 * event plumbing can be covered without a server.
 */
final class NourishmentEatRules {

    static final int FULL_FOOD_LEVEL = 20;

    private NourishmentEatRules() {
    }

    /**
     * Whether the interaction should briefly lower the food level. Always-edible foods and players below full
     * hunger already consume natively and are left alone; a denied item-use slot is left to the other handlers.
     */
    static boolean shouldLower(boolean enabled, boolean itemUseDenied, boolean hasNourishment, int foodLevel,
                               boolean edible, boolean canAlwaysEat) {
        return enabled
                && !itemUseDenied
                && hasNourishment
                && foodLevel >= FULL_FOOD_LEVEL
                && edible
                && !canAlwaysEat;
    }

    /** The level that lets vanilla start a consume: one below full. */
    static int loweredFoodLevel(int foodLevel) {
        return Math.max(0, foodLevel - 1);
    }

    /**
     * The value to restore after the brief lowering. Only the exact temporary drop (one below the original) is
     * repaired; if something else changed the level in the meantime, that value wins.
     */
    static int restoredFoodLevel(int currentFoodLevel, int originalFoodLevel) {
        return currentFoodLevel == originalFoodLevel - 1 ? originalFoodLevel : currentFoodLevel;
    }
}
