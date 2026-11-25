package com.huidu.farmersdelight.loot;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A lethal Fire Aspect hit must be visible to the death/loot evaluation, but only for a short TTL. */
class BurningDeathTrackerTest {

    @AfterEach
    void clear() {
        BurningDeathTracker.clear();
    }

    @Test
    void aMarkedLethalHitReadsAsBurningUntilItExpires() {
        UUID victim = UUID.randomUUID();
        BurningDeathTracker.mark(victim);

        assertTrue(BurningDeathTracker.wasBurning(victim));
        assertFalse(BurningDeathTracker.wasBurning(victim, System.nanoTime() + BurningDeathTracker.TTL_NANOS + 1_000_000L));
        assertFalse(BurningDeathTracker.wasBurning(victim), "an expired mark must be pruned");
    }

    @Test
    void theLootConditionsUseTheTracker() throws IOException {
        String burning = read("src/main/java/com/huidu/farmersdelight/condition/IsBurningCondition.java");
        assertTrue(burning.contains("BurningDeathTracker.wasBurning("),
                "is_burning must accept a lethal Fire Aspect hit, not only live fire ticks");

        String knife = read("src/main/java/com/huidu/farmersdelight/loot/KnifeDropHandler.java");
        assertTrue(knife.contains("BurningDeathTracker.wasBurning(entity)"),
                "the addon knife rules must use the same burning decision");

        String tracker = read("src/main/java/com/huidu/farmersdelight/loot/BurningDeathTracker.java");
        assertFalse(tracker.contains("getItemInOffHand()"),
                "vanilla Fire Aspect is main-hand only; counting the offhand mislabels unenchanted kills");
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
