import com.huidu.farmersdelight.api.recipe.IngredientMatching;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Arrays;
import java.util.function.BiPredicate;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngredientMatchingTest {

    private static final BiPredicate<String, String> EQ = String::equals;
    private static final ToIntFunction<String> ONE = s -> 1;

    private static boolean exact(List<String> required, List<String> slots) {
        return IngredientMatching.matchesIngredients(required, slots, true, EQ, ONE);
    }

    private static boolean lenient(List<String> required, List<String> slots) {
        return IngredientMatching.matchesIngredients(required, slots, false, EQ, ONE);
    }

    @Test
    void exactPassMatchesOneToOne() {
        assertTrue(exact(List.of("rice"), List.of("rice")));
        assertTrue(exact(List.of("rice", "kelp"), List.of("rice", "kelp")));
    }

    @Test
    void exactPassRejectsExtraSlotsEvenWhenTheyMatch() {
        assertFalse(exact(List.of("rice"), List.of("rice", "rice", "rice")));
    }

    @Test
    void exactPassMatchesIngredientCountToSlotCount() {
        assertTrue(exact(List.of("beetroot", "beetroot", "beetroot", "beetroot"),
                         List.of("beetroot", "beetroot", "beetroot", "beetroot")));
    }

    @Test
    void exactPassRejectsTooFewSlots() {
        assertFalse(exact(List.of("rice", "kelp"), List.of("rice")));
    }

    @Test
    void lenientPassMatchesSameIngredientSpreadAcrossSlots() {
        // The original cooking-pot bug: rice in 3 slots for a 1-rice recipe was rejected.
        assertTrue(lenient(List.of("rice"), List.of("rice", "rice", "rice")));
    }

    @Test
    void lenientPassRejectsForeignItem() {
        // Lenient still blocks unrelated items so the recipe can't be confused by extra junk.
        assertFalse(lenient(List.of("rice"), List.of("rice", "tnt")));
    }

    @Test
    void lenientPassRejectsWhenWrongIngredientPresent() {
        assertFalse(lenient(List.of("beetroot", "beetroot", "beetroot", "beetroot"),
                            List.of("beetroot", "beetroot", "beetroot", "carrot")));
    }

    @Test
    void lenientPassRejectsTooFewSlots() {
        assertFalse(lenient(List.of("rice", "kelp"), List.of("rice")));
    }

    @Test
    void orderIndependentMatching() {
        assertTrue(exact(List.of("rice", "kelp"), List.of("kelp", "rice")));
        assertTrue(lenient(List.of("rice", "kelp"), List.of("kelp", "rice", "rice")));
    }

    @Test
    void emptyRequiredEmptySlotsMatchesExact() {
        assertTrue(exact(List.of(), List.of()));
    }

    @Test
    void emptyRequiredWithFilledSlotsFailsExactPassesLenient() {
        // exactSlots requires the counts equal; lenient requires only that every slot is usable, but with zero
        // required ingredients no slot is usable, so a filled slot fails lenient too. (Empty-required recipes
        // aren't a real case but this nails down the corner.)
        assertFalse(exact(List.of(), List.of("rice")));
        assertFalse(lenient(List.of(), List.of("rice")));
    }

    @Test
    void slotCountFloorPreventsSingleHighAmountSlotFromCoveringManyIngredients() {
        // Mirrors the cooking pot's existing behavior: a slot is one ingredient position. Even when a slot
        // has amount 3, it does NOT satisfy three single-unit ingredients on its own — there must be at least
        // requiredCount filled slots. (Spreading the same ingredient across multiple slots IS allowed; that's
        // the lenientPassMatchesSameIngredientSpreadAcrossSlots case.)
        ToIntFunction<String> amountThree = s -> 3;
        assertFalse(IngredientMatching.matchesIngredients(
                List.of("rice", "rice", "rice"), List.of("rice"), false, EQ, amountThree));
    }

    @Test
    void slotAmountConsumedAcrossIngredientsAtMatchingSlotCount() {
        // 3 slots, 3 ingredients (passes the slot-count floor). Each slot's amount is decremented per ingredient
        // match. With amount=2 per slot the algorithm correctly stops after each slot contributes one unit per
        // matching ingredient.
        ToIntFunction<String> amountTwo = s -> 2;
        assertTrue(IngredientMatching.matchesIngredients(
                List.of("rice", "rice", "rice"), List.of("rice", "rice", "rice"), false, EQ, amountTwo));
    }

    @Test
    void zeroAmountSlotIsTreatedAsAbsent() {
        // A slot with amount 0 contributes nothing to consumption; if requiredCount > usable slots, no match.
        ToIntFunction<String> amount = s -> "rice".equals(s) ? 1 : 0;
        assertFalse(IngredientMatching.matchesIngredients(
                List.of("rice", "rice"), List.of("rice", "kelp"), false, EQ, amount));
    }

    // A slot satisfies the broad "fish" ingredient if it is any fish, but the narrow "cod"/"salmon"
    // ingredients only match their own item. This mirrors a tag-or-choice ingredient listed alongside a
    // specific item, where the specific item is a subset of the broad one.
    private static final BiPredicate<String, String> FISH = (slot, ingredient) -> switch (ingredient) {
        case "fish" -> slot.equals("cod") || slot.equals("salmon");
        default -> slot.equals(ingredient);
    };

    @Test
    void overlappingIngredientsMatchRegardlessOfSlotOrder() {
        // Greedy first-fit fails one of these orderings: the broad "fish" grabs the only cod, leaving the
        // narrow "cod" ingredient unmatched. A proper bipartite matching succeeds for BOTH orderings.
        assertTrue(IngredientMatching.matchesIngredients(
                List.of("fish", "cod"), List.of("cod", "salmon"), true, FISH, ONE));
        assertTrue(IngredientMatching.matchesIngredients(
                List.of("fish", "cod"), List.of("salmon", "cod"), true, FISH, ONE));
    }

    @Test
    void overlappingIngredientsBroadFirstLenient() {
        // Same overlap trap under the lenient pass (extra usable slots allowed).
        assertTrue(IngredientMatching.matchesIngredients(
                List.of("fish", "cod"), List.of("cod", "salmon", "salmon"), false, FISH, ONE));
    }

    @Test
    void assignmentUsesTheSameAugmentingMatchAsValidation() {
        int[] assignment = IngredientMatching.assignIngredients(
                List.of("fish", "cod"), List.of("cod", "salmon"), FISH, ONE);
        assertArrayEquals(new int[] {1, 0}, assignment);
    }

    @Test
    void assignmentPreservesEmptySlotIndexesAndUsesEachFilledSlot() {
        var slots = Arrays.asList(null, "rice", null, "rice", "rice", null);
        int[] assignment = IngredientMatching.assignIngredients(
                List.of("rice", "rice", "rice"), slots, EQ, slot -> slot == null ? 0 : 1);
        Arrays.sort(assignment);
        assertArrayEquals(new int[] {1, 3, 4}, assignment);
    }

    @Test
    void overlappingIngredientsStillRejectWhenNarrowItemAbsent() {
        // No cod present, so "cod" can never be satisfied even though two slots match "fish".
        assertFalse(IngredientMatching.matchesIngredients(
                List.of("fish", "cod"), List.of("salmon", "salmon"), true, FISH, ONE));
    }
}
