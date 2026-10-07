package com.huidu.farmersdelight.manager;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Closing a handheld bar has to resend the slot as it is now: the stack can have been dropped, eaten or moved
 * while the bar was up, and resending the copy captured when it opened would tell the client a stack the server
 * no longer has — the shape of an inventory desync that lets a stale item be moved around.
 *
 * The netty channel the bar needs cannot be built without a running server, so this is asserted on the source,
 * together with the region rule the read itself has to obey.
 */
class HandheldDisplaysSourceTest {

    @Test
    void theCloseResendsTheCurrentSlot() throws IOException {
        String body = methodBody(read("manager/HandheldDisplays.java"), "private void sendRealSlot()");

        assertTrue(body.contains("getInventory().getItem(this.slot)"),
                "the resend has to read the slot as it is now");
        assertFalse(body.contains("this.original"),
                "and never resend the copy the bar was opened with");
        assertTrue(body.contains("Bukkit.isOwnedByCurrentRegion(this.player)"),
                "reading the slot stays on the player's own region");
    }

    @Test
    void theHandlerIsGoneBeforeTheRealSlotIsSent() throws IOException {
        String body = methodBody(read("manager/HandheldDisplays.java"), "public void close()");

        int handler = body.indexOf("this.display.close()");
        int resend = body.indexOf("sendRealSlot()");
        assertTrue(handler > 0 && resend > handler,
                "the packet rewrite has to be removed before the real slot is resent");
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
