package com.huidu.farmersdelight.listener;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Folia fires entity damage events on a region that may not own the attacker. Handlers must not read/write the
 * attacker's inventory from that region: either do the work synchronously only when both entities are owned, or
 * hop the mutation to the player's entity scheduler.
 */
class DamageEventOwnershipContractTest {

    @Test
    void toolAttackDispatchesAttackerState() throws IOException {
        String source = read("src/main/java/com/huidu/farmersdelight/tool/ToolAttackListener.java");
        assertTrue(source.contains("owns(player)"), "tool attack must gate attacker-owned state");
        assertTrue(source.contains("runForEntity(player"), "the attacker's sound/durability must hop to its region");
    }

    @Test
    void backstabRequiresBothEntitiesOwned() throws IOException {
        String source = read("src/main/java/com/huidu/farmersdelight/listener/BackstabListener.java");
        assertTrue(source.contains("!owns(attacker) || !owns(target)"),
                "backstab must not read the attacker/target across regions");
    }

    @Test
    void skilletLifecycleDispatchesTheAttackerStop() throws IOException {
        String source = read("src/main/java/com/huidu/farmersdelight/listener/SkilletLifecycleListener.java");
        assertTrue(source.contains("isOwnedByCurrentRegion(player)"),
                "the attack cleanup must check player ownership");
        assertTrue(source.contains("runForEntity(player"), "the stop must hop to the player's region");
    }

    @Test
    void burningTrackerDoesNotReadAForeignAttacker() throws IOException {
        String source = read("src/main/java/com/huidu/farmersdelight/loot/BurningDeathTracker.java");
        assertTrue(source.contains("isOwnedByCurrentRegion(victim)"),
                "the lethal-hit tracker must verify the victim region");
        assertTrue(source.contains("isOwnedByCurrentRegion(attacker)"),
                "the lethal-hit tracker must verify the attacker region");
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
