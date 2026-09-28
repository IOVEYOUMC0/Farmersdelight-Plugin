import com.huidu.farmersdelight.api.recipe.IngredientMatchMemo;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngredientMatchMemoTest {

    private static final AtomicInteger CALLS = new AtomicInteger();

    private static BiPredicate<String, String> countingMatcher() {
        CALLS.set(0);
        return (slot, ingredient) -> {
            CALLS.incrementAndGet();
            return ingredient.startsWith(slot);
        };
    }

    @Test
    void cachesPerExpressionAndSlot() {
        IngredientMatchMemo<String, String> memo =
                IngredientMatchMemo.of(countingMatcher(), ingredient -> "k:" + ingredient);

        assertTrue(memo.test("a", "abc"));
        assertTrue(memo.test("a", "abc"));
        assertEquals(1, CALLS.get(), "an answered (expression, slot) pair must not be matched twice");

        assertFalse(memo.test("b", "abc"));
        assertFalse(memo.test("b", "abc"));
        assertEquals(2, CALLS.get(), "the same expression against another slot is a separate answer");
    }

    @Test
    void differentExpressionsDoNotShareAnswers() {
        IngredientMatchMemo<String, String> memo =
                IngredientMatchMemo.of(countingMatcher(), ingredient -> "k:" + ingredient);

        assertTrue(memo.test("a", "abc"));
        // Same slot, different expression: must be matched on its own, even though the slot was seen before.
        assertFalse(memo.test("a", "xyz"));
        assertEquals(2, CALLS.get());
    }

    @Test
    void equalButDistinctSlotsDoNotShareAnswers() {
        IngredientMatchMemo<String, String> memo =
                IngredientMatchMemo.of(countingMatcher(), ingredient -> "k:" + ingredient);

        String first = new String("ab");
        String second = new String("ab");
        assertTrue(memo.test(first, "abc"));
        assertTrue(memo.test(second, "abc"));
        assertEquals(2, CALLS.get(), "slots are compared by identity, never by equality");
    }

    @Test
    void unmemoizableExpressionsFallBackToTheMatcher() {
        IngredientMatchMemo<String, String> memo =
                IngredientMatchMemo.of(countingMatcher(), ingredient -> null);

        assertTrue(memo.test("a", "abc"));
        assertTrue(memo.test("a", "abc"));
        assertEquals(2, CALLS.get(), "a null key means no caching at all");
    }
}
