package com.huidu.farmersdelight.api.recipe;

import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.ToIntFunction;

public final class IngredientMatching {

    private IngredientMatching() {
    }

    public static <Slot, Ingredient> boolean matchesIngredients(
            List<Ingredient> required,
            List<Slot> slots,
            boolean exactSlots,
            BiPredicate<Slot, Ingredient> matcher,
            ToIntFunction<Slot> initialAmount) {
        int n = slots.size();
        int requiredCount = required.size();

        if (exactSlots) {
            if (requiredCount != n) {
                return false;
            }
        } else {
            if (n < requiredCount) {
                return false;
            }
            for (Slot slot : slots) {
                boolean usable = false;
                for (Ingredient ingredient : required) {
                    if (matcher.test(slot, ingredient)) {
                        usable = true;
                        break;
                    }
                }
                if (!usable) {
                    return false;
                }
            }
        }

        return assignAll(required, slots, matcher, initialAmount);
    }

    public static <Slot, Ingredient> boolean containsIngredients(
            List<Ingredient> required,
            List<Slot> slots,
            BiPredicate<Slot, Ingredient> matcher,
            ToIntFunction<Slot> initialAmount) {
        return assignAll(required, slots, matcher, initialAmount);
    }

    private static <Slot, Ingredient> boolean assignAll(
            List<Ingredient> required,
            List<Slot> slots,
            BiPredicate<Slot, Ingredient> matcher,
            ToIntFunction<Slot> initialAmount) {
        int n = slots.size();
        int requiredCount = required.size();

        int[] remaining = new int[n];
        for (int i = 0; i < n; i++) {
            remaining[i] = Math.max(0, initialAmount.applyAsInt(slots.get(i)));
        }

        // Assign each required ingredient to one available slot-unit it satisfies. Greedy first-fit (take
        // the first matching slot and never reconsider) is NOT a valid assignment: when ingredient specs
        // overlap and a broad spec (a tag / choice) is listed before a narrower one that is a subset of it,
        // the broad spec can grab the only slot the narrow spec needs, missing a match that exists. Model it
        // as bipartite matching and use augmenting paths so a feasible assignment is found regardless of the
        // ingredient or slot order. A slot with amount a offers up to min(a, requiredCount) interchangeable
        // units (no ingredient needs more than one unit, and there are only requiredCount ingredients).
        int totalUnits = 0;
        for (int i = 0; i < n; i++) {
            totalUnits += Math.min(remaining[i], requiredCount);
        }
        int[] unitSlot = new int[totalUnits];
        int u = 0;
        for (int i = 0; i < n; i++) {
            int cap = Math.min(remaining[i], requiredCount);
            for (int c = 0; c < cap; c++) {
                unitSlot[u++] = i;
            }
        }
        int[] unitOwner = new int[totalUnits]; // ingredient index currently holding this unit, or -1
        java.util.Arrays.fill(unitOwner, -1);
        for (int k = 0; k < requiredCount; k++) {
            boolean[] visited = new boolean[totalUnits];
            if (!assign(k, required, slots, unitSlot, unitOwner, visited, matcher)) {
                return false;
            }
        }
        return true;
    }

    private static <Slot, Ingredient> boolean assign(
            int k,
            List<Ingredient> required,
            List<Slot> slots,
            int[] unitSlot,
            int[] unitOwner,
            boolean[] visited,
            BiPredicate<Slot, Ingredient> matcher) {
        Ingredient ingredient = required.get(k);
        for (int unit = 0; unit < unitSlot.length; unit++) {
            if (visited[unit] || !matcher.test(slots.get(unitSlot[unit]), ingredient)) {
                continue;
            }
            visited[unit] = true;
            if (unitOwner[unit] == -1
                    || assign(unitOwner[unit], required, slots, unitSlot, unitOwner, visited, matcher)) {
                unitOwner[unit] = k;
                return true;
            }
        }
        return false;
    }
}
