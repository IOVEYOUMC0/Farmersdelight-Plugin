package com.huidu.farmersdelight.api.recipe;

/**
 * Describes one editable numeric field in the generic recipe editor (e.g. ferment time, experience).
 * The editor renders a button; left-click adds step, right-click subtracts, clamped to [min, max].
 *
 * key the field key used in EditableRecipe number storage
 * label display label shown on the editor button
 * step amount added/removed per click
 * min lower bound (inclusive)
 * max upper bound (inclusive)
 * decimals digits to show after the decimal point (0 = integer)
 */
public record NumericField(String key, String label, double step, double min, double max, int decimals) {
}
