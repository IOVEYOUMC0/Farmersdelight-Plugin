package com.huidu.farmersdelight.api.recipe;

/**
 * The result of a recipe-book Fill action, mirroring the cooking pot's fill-button states so a station's
 * recipe book can give the same feedback: the button reflects whether ingredients moved, were missing, or
 * could not fit.
 */
public enum FillOutcome {

    /** Ingredients were moved into the station (or it already held the full recipe). */
    FILLED,

    /** The player's inventory lacks the recipe's ingredients, so nothing was moved. */
    MISSING_INGREDIENTS,

    /** The station's slots are full, so at least one ingredient could not be placed. */
    INVENTORY_FULL,

    /** Nothing happened and no specific reason applies (e.g. the station is gone). */
    NOTHING
}
