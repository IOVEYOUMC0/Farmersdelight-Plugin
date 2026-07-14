package com.huidu.farmersdelight.api.recipe;

/**
 * Describes one editable numeric field in the generic recipe editor (e.g. ferment time, experience).
 * The editor renders a button; left-click adds step, right-click subtracts, clamped to [min, max].
 *
 * @param key      the field key used in EditableRecipe number storage
 * @param label    display label shown on the editor button
 * @param step     amount added/removed per click
 * @param min      lower bound (inclusive)
 * @param max      upper bound (inclusive)
 * @param decimals digits to show after the decimal point (0 = integer)
 */
public record NumericField(String key, String label, double step, double min, double max, int decimals) {
}
