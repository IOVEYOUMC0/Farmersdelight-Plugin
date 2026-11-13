package com.huidu.farmersdelight.manager;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source contract: the three station loops claim and release their in-flight marker through the guard, which
 * expires a claim whose region task never ran, instead of a plain set that would keep the block marked forever.
 */
class InFlightTickContractTest {

    private static final Map<String, String> MARKERS = Map.of(
            "TickManager.java", "scheduledActiveBlocks",
            "StoveManager.java", "scheduledStoveTicks",
            "SkilletManager.java", "scheduledSkilletTicks");

    @Test
    void eachStationLoopClaimsAndReleasesThroughTheGuard() throws IOException {
        for (Map.Entry<String, String> entry : MARKERS.entrySet()) {
            String source = read("src/main/java/com/huidu/farmersdelight/manager/" + entry.getKey());
            String marker = entry.getValue();
            assertTrue(source.contains(marker + " = new InFlightGuard<>("), entry.getKey());
            assertTrue(source.contains(marker + ".tryAdd("), entry.getKey());
            assertTrue(source.contains(marker + ".complete("), entry.getKey());
            assertFalse(source.contains(marker + ".add("), entry.getKey() + " must claim through the guard");
            assertFalse(source.contains(marker + ".remove("), entry.getKey() + " must release through the guard");
        }
    }

    private static String read(String relative) throws IOException {
        for (String prefix : new String[]{"", "FarmersDelight/"}) {
            Path path = Path.of(prefix + relative);
            if (Files.isRegularFile(path)) return Files.readString(path);
        }
        throw new IOException("source not found: " + relative);
    }
}
