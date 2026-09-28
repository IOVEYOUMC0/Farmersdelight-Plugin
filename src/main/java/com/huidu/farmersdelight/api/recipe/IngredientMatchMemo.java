package com.huidu.farmersdelight.api.recipe;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * Memoizes ingredient matching for one draw. A page tests the same handful of ingredient expressions
 * against the same inventory slot list for every recipe it draws, and each test resolves CraftEngine item
 * ids and tags, so the answer is cached per (expression key, slot) for the lifetime of the memo.
 *
 * <p>The caller owns the lifetime and supplies the key: it must be equal for any two ingredients that match
 * exactly the same stacks, and different for any two that do not. Slots are compared by identity, so a
 * caller must keep the same slot instances for the whole memo (one inventory snapshot per draw) — two equal
 * but distinct stacks must never share a cached answer.
 *
 * <p>An ingredient whose key function returns null is matched directly instead of being cached.
 */
public final class IngredientMatchMemo<Slot, Ingredient> implements BiPredicate<Slot, Ingredient> {

    private final BiPredicate<Slot, Ingredient> matcher;
    private final Function<Ingredient, String> keyFunction;
    private final Map<String, Map<Slot, Boolean>> answersByExpression = new HashMap<>();

    private IngredientMatchMemo(BiPredicate<Slot, Ingredient> matcher, Function<Ingredient, String> keyFunction) {
        this.matcher = matcher;
        this.keyFunction = keyFunction;
    }

    public static <Slot, Ingredient> IngredientMatchMemo<Slot, Ingredient> of(
            BiPredicate<Slot, Ingredient> matcher,
            Function<Ingredient, String> keyFunction) {
        return new IngredientMatchMemo<>(matcher, keyFunction);
    }

    @Override
    public boolean test(Slot slot, Ingredient ingredient) {
        String key = keyFunction.apply(ingredient);
        if (key == null) {
            return matcher.test(slot, ingredient);
        }
        Map<Slot, Boolean> answersBySlot = answersByExpression.get(key);
        if (answersBySlot == null) {
            answersBySlot = new IdentityHashMap<>();
            answersByExpression.put(key, answersBySlot);
        }
        Boolean cached = answersBySlot.get(slot);
        if (cached != null) {
            return cached;
        }
        boolean answer = matcher.test(slot, ingredient);
        answersBySlot.put(slot, answer);
        return answer;
    }
}
