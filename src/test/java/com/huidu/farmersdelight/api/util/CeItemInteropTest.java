package com.huidu.farmersdelight.api.util;

import net.momirealms.craftengine.core.item.Item;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Covers the guards of the CraftEngine item conversion, which are the part that decides whether a
 * conversion is attempted at all.
 *
 *
 * The item is a dynamic proxy, so the paths that return before CraftEngine is asked are asserted without a
 * server. The transfer itself — ItemStackUtils.getBukkitStack(item.minecraftItem()) — resolves the
 * running CraftEngine instance and can only be exercised on a server; the tests below assert that a null or
 * empty item never reaches it.
 */
class CeItemInteropTest {

    private final List<String> calls = new ArrayList<>();

    @Test
    void aNullItemConvertsToNull() {
        assertNull(CeItemInterop.asBukkitStack(null));
        assertNull(CeItemInterop.toBukkitStack(null));
    }

    @Test
    void anEmptyItemNeverReachesTheConversion() {
        assertNull(CeItemInterop.asBukkitStack(item(true, 1)));

        assertEquals(List.of("isEmpty"), calls, "an empty item stops at the emptiness check");
    }

    @Test
    void aUsableItemIsCopiedWithItsOwnCount() {
        Item copy = item(false, 99);
        Item item = item(false, 3, copy);

        assertSame(copy, CeItemInterop.normalize(item));
        // The count is read once for the guard and once for the copy, which is what the call sites this
        // replaced did as well.
        assertEquals(List.of("isEmpty", "count", "count", "copyWithCount(3)"), calls,
                "normalize only asks the item about itself");
    }

    private Item item(boolean empty, int count) {
        return item(empty, count, null);
    }

    /** A CraftEngine item with fixed answers: every question it is asked is recorded. */
    private Item item(boolean empty, int count, Item copyResult) {
        return (Item) Proxy.newProxyInstance(Item.class.getClassLoader(), new Class<?>[]{Item.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "isEmpty":
                            calls.add("isEmpty");
                            return empty;
                        case "count":
                            calls.add("count");
                            return count;
                        case "copyWithCount":
                            calls.add("copyWithCount(" + args[0] + ")");
                            return copyResult == null ? proxy : copyResult;
                        case "toString":
                            return "fake Item";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            throw new AssertionError("unexpected Item call: " + method.getName());
                    }
                });
    }
}
