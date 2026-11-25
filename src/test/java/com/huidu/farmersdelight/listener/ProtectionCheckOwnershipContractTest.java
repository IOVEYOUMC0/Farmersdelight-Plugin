package com.huidu.farmersdelight.listener;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** ProtectionChecks reads the player's position; ownership must be checked before it, not after. */
class ProtectionCheckOwnershipContractTest {

    @Test
    void blockBreakChecksOwnershipBeforeProtection() throws IOException {
        String source = read("src/main/java/com/huidu/farmersdelight/listener/BlockBreakListener.java");
        int method = source.indexOf("public void onBlockBreak(BlockBreakEvent event)");
        int end = source.indexOf("public void onCustomBlockBreak(CustomBlockBreakEvent event)", method + 1);
        assertBefore(source.substring(method, end), "boolean owned = owns(breaker)", "ProtectionChecks.canBreak");
    }

    @Test
    void customBlockBreakChecksOwnershipBeforeProtection() throws IOException {
        String source = read("src/main/java/com/huidu/farmersdelight/listener/BlockBreakListener.java");
        int method = source.indexOf("public void onCustomBlockBreak(CustomBlockBreakEvent event)");
        int end = source.indexOf("private void cleanupManagedBreak", method + 1);
        assertBefore(source.substring(method, end), "boolean owned = owns(breaker)", "ProtectionChecks.canBreak");
    }

    private static void assertBefore(String body, String ownershipNeedle, String protectionNeedle) {
        int ownership = body.indexOf(ownershipNeedle);
        int protection = body.indexOf(protectionNeedle);
        assertTrue(ownership >= 0, "missing ownership guard: " + ownershipNeedle);
        assertTrue(protection >= 0, "missing protection call: " + protectionNeedle);
        assertTrue(ownership < protection, "ownership guard must run before " + protectionNeedle);
    }

    private static String read(String relative) throws IOException {
        for (String prefix : new String[]{"", "FarmersDelight/"}) {
            Path path = Path.of(prefix, relative);
            if (Files.isRegularFile(path)) {
                return Files.readString(path);
            }
        }
        throw new IOException("source not found: " + relative);
    }
}
