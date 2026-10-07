package com.huidu.farmersdelight.recipe;

/**
 * The per-tick budget a manager's registration round runs under.
 *
 *
 * The CraftEngine readiness pass rebuilds the recipe managers and then keeps going in the same tick: it warms
 * the recipe-backed caches and prints the content summary out of the published recipe set. A load that has
 * nothing published yet, which is the first load of a boot, therefore runs whole in the round's inline slice,
 * so that pass reads the complete set instead of an empty one. A later load (a reload, an external republish)
 * has a published set it keeps serving players from and stays on the configured budget, so a large file never
 * registers every entry in one tick.
 */
final class RecipeRegistrationBudget {

    /** A budget that covers every entry of the round; the round clamps it to the entries it actually has. */
    static final int WHOLE_LOAD = Integer.MAX_VALUE;

    private RecipeRegistrationBudget() {
    }

    /** The budget for one load: whole while nothing is published, otherwise the configured budget. */
    static int forLoad(int publishedRecipes, int configuredBudget) {
        return publishedRecipes > 0 ? Math.max(1, configuredBudget) : WHOLE_LOAD;
    }
}
