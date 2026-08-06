package com.huidu.farmersdelight.api.recipe;

public record NumericField(String key, String label, double step, double min, double max, int decimals) {
}
