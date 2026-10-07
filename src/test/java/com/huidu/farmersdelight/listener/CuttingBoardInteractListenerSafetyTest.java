package com.huidu.farmersdelight.listener;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sneak insert stores a tool on the board and consumes the hand item only once the store committed. A
 * store that throws before the consumption would leave the tool on the board and in the hand at the same
 * time, so the guard, its rollback and the order of the two steps are what this suite pins.
 */
class CuttingBoardInteractListenerSafetyTest {

    @Test
    void aFailedStoreRollsBackAndConsumesNothing() {
        List<String> calls = new ArrayList<>();

        boolean stored = CuttingBoardInteractListener.storeThenConsume(
                () -> {
                    calls.add("store");
                    throw new IllegalStateException("region thread check");
                },
                () -> calls.add("rollback"),
                () -> calls.add("consume"));

        assertFalse(stored, "a throwing store must not report success");
        assertEquals(List.of("store", "rollback"), calls,
                "the failed store is rolled back and the hand item is never consumed");
    }

    @Test
    void aSuccessfulStoreConsumesExactlyOnce() {
        List<String> calls = new ArrayList<>();

        boolean stored = CuttingBoardInteractListener.storeThenConsume(
                () -> calls.add("store"),
                () -> calls.add("rollback"),
                () -> calls.add("consume"));

        assertTrue(stored, "a committed store reports success");
        assertEquals(List.of("store", "consume"), calls,
                "a committed store consumes the hand item once and never rolls back");
    }

    @Test
    void aFailingRollbackStillConsumesNothing() {
        List<String> calls = new ArrayList<>();

        boolean stored = CuttingBoardInteractListener.storeThenConsume(
                () -> {
                    throw new LinkageError("unresolved platform class");
                },
                () -> {
                    calls.add("rollback");
                    throw new IllegalStateException("rollback failed too");
                },
                () -> calls.add("consume"));

        assertFalse(stored, "a failed rollback still reports the store as failed");
        assertEquals(List.of("rollback"), calls, "the hand item is what must never be consumed");
    }

    /**
     * The wiring, not only the helper: the sneak insert has to run its board store through the guard. It is
     * asserted on the source because driving the listener needs a CraftEngine event and a live block state.
     */
    @Test
    void theSneakInsertStoresThroughTheGuardedCall() throws IOException {
        String source = read("listener/CuttingBoardInteractListener.java");
        int call = source.indexOf("storeThenConsume(");
        assertTrue(call > 0, "the sneak insert has to store through the rollback guard");
        int end = matchingParen(source, source.indexOf('(', call));
        String guarded = source.substring(call, end + 1);
        assertTrue(guarded.contains(".setItem("), "the board store is the first guarded step");
        assertTrue(guarded.contains(".setStoredItem(null"), "the rollback empties the board again");
        assertTrue(guarded.contains("setItemInMainHand("), "the hand is consumed inside the guarded call");
    }

    /** The index of the parenthesis that closes the one at the given index. */
    private static int matchingParen(String source, int open) {
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        throw new AssertionError("unbalanced parentheses from index " + open);
    }

    /** Finds the source from the test working directory the same way the other source assertions do. */
    private static String read(String relative) throws IOException {
        Path cursor = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 4 && cursor != null; depth++) {
            for (String prefix : new String[]{"FarmersDelight/src/main/java/com/huidu/farmersdelight/",
                    "src/main/java/com/huidu/farmersdelight/"}) {
                Path candidate = cursor.resolve(prefix + relative);
                if (Files.isRegularFile(candidate)) {
                    return Files.readString(candidate);
                }
            }
            cursor = cursor.getParent();
        }
        assertNotNull(null, "the source file has to be reachable from the test working directory: " + relative);
        return "";
    }
}
