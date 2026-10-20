package com.huidu.farmersdelight.recipe;

/**
 * The per-tick budget a manager's registration round runs under.
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
