package com.huidu.farmersdelight.manager;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The handheld bar only reaches the client because the shared rewriter replaces the server's own inventory sync
 * for that slot. That replacement used to require a stack of exactly one item, which was invisible for the
 * handheld skillet (always a single item) and wrong for the skewer, which cooks one item out of a stack: with two
 * or more the rewriter went inert and the bar flickered between the display copy and the real stack.
 *
 * These cases pin the count-agnostic claim and the display construction that keeps the item it paints stable.
 */
class HandheldDisplayReclaimTest {

    /** A stack of any size is the real stack; only an empty one or another item is not. */
    @Test
    void aStackOfMoreThanOneIsStillTheRealStack() {
        assertTrue(HandheldCookingDisplay.matchesRealStack(2, true),
                "a stack of two skewers is the stack the bar belongs to");
        assertTrue(HandheldCookingDisplay.matchesRealStack(64, true), "any count counts");
        assertTrue(HandheldCookingDisplay.matchesRealStack(1, true), "one item still counts");
        assertFalse(HandheldCookingDisplay.matchesRealStack(0, true),
                "an empty slot is never ours, so the display copy cannot resurrect it");
        assertFalse(HandheldCookingDisplay.matchesRealStack(2, false),
                "another item in the slot is never ours");
    }

    /** The claim itself is the count-agnostic predicate, not a leftover count == 1 check. */
    @Test
    void theClaimNoLongerRequiresASingleItem() throws IOException {
        String source = read("manager/HandheldCookingDisplay.java");
        assertTrue(source.contains("matchesRealStack(ItemStackProxy.INSTANCE.getCount(item)"),
                "the slot claim has to go through the count-agnostic predicate");
        assertTrue(source.contains("return itemCount > 0 && sameItemAndComponents;"),
                "and that predicate has to accept any non-empty count");
        assertFalse(source.contains("getCount(item) == 1"),
                "the single-item requirement was the flicker: it must not come back");
    }

    /** The painted item is rebuilt from the slot as it is now, and only repainted when its value changes. */
    @Test
    void theDisplayPaintsALiveStackAndSkipsIdenticalRepaints() throws IOException {
        String source = read("manager/HandheldDisplays.java");
        String update = methodBody(source, "public void update(int progress)");

        assertTrue(update.contains("currentStack()"),
                "the display has to be built from the slot as it is now");
        assertTrue(update.contains("damage == this.displayedDamage"),
                "an identical bar must not be re-sent");
        assertTrue(update.contains("setJavaComponent(DataComponentKeys.DAMAGE, damage)"),
                "the per-update change is the damage value only");

        int maxDamageWrites = count(source, "setJavaComponent(DataComponentKeys.MAX_DAMAGE");
        assertEquals(1, maxDamageWrites,
                "max damage is written once per rebuilt base, not on every update");
        assertTrue(source.contains("base.isSimilar(source)"),
                "the base is only rebuilt when the slot no longer holds the same stack");
        assertTrue(methodBody(source, "private ItemStack currentStack()")
                        .contains("getInventory().getItem(this.slot)"),
                "the live read is the same slot read the close path already does");
    }

    private static int count(String source, String needle) {
        int total = 0;
        int index = source.indexOf(needle);
        while (index >= 0) {
            total++;
            index = source.indexOf(needle, index + needle.length());
        }
        return total;
    }

    /** The body of the method with the given signature, so the shape can be asserted on the source. */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start > 0, "the method has to exist: " + signature);
        int open = source.indexOf('{', start);
        assertTrue(open > start, "the method has to have a body: " + signature);
        return source.substring(open + 1, matchingBrace(source, open));
    }

    /** The index of the brace that closes the one at the given index, ignoring line comments. */
    private static int matchingBrace(String source, int open) {
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '/' && index + 1 < source.length() && source.charAt(index + 1) == '/') {
                index = source.indexOf('\n', index);
                if (index < 0) {
                    break;
                }
                continue;
            }
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        throw new AssertionError("unbalanced braces from index " + open);
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
