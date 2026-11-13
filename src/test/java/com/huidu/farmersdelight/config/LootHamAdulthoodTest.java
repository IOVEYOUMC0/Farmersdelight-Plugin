package com.huidu.farmersdelight.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Upstream's ham loot modifiers have no adult gate: a knife kill on a baby pig or hoglin still drops the ham.
 * The four ham pools this plugin ships must match that. The other knife-loot pools keep their adult gate for now
 * (a wider gameplay change that needs an owner decision).
 */
class LootHamAdulthoodTest {

    private static final List<String> HAM_ENTRIES = List.of(
            "farmersdelight:ham_from_pig:",
            "farmersdelight:smoked_ham_from_burning_pig:",
            "farmersdelight:ham_from_hoglin:",
            "farmersdelight:smoked_ham_from_burning_hoglin:");

    @Test
    void hamLootHasNoAdultGate() throws IOException {
        String yaml = read("src/main/resources/craftengine/farmersdelight/configuration/vanilla_loots.yml");
        for (String entry : HAM_ENTRIES) {
            String section = section(yaml, entry);
            assertTrue(section != null, entry + " has to stay in the loot file");
            assertFalse(section.contains("type: farmersdelight:is_adult"),
                    entry + " must not gate the ham on adulthood, matching the upstream loot modifier");
        }
    }

    @Test
    void knifeLootEntriesKeepTheOtherConditions() throws IOException {
        String yaml = read("src/main/resources/craftengine/farmersdelight/configuration/vanilla_loots.yml");
        for (String entry : HAM_ENTRIES) {
            String section = section(yaml, entry);
            assertTrue(section.contains("type: farmersdelight:is_knife"),
                    entry + " still has to require a knife");
            assertTrue(section.contains("type: has_player"), entry + " still has to require a player kill");
        }
    }

    private static String section(String yaml, String key) {
        int start = yaml.indexOf("  " + key);
        if (start < 0) return null;
        int next = yaml.indexOf("\n  farmersdelight:", start + key.length() + 2);
        return next < 0 ? yaml.substring(start) : yaml.substring(start, next);
    }

    private static String read(String relative) throws IOException {
        for (String prefix : new String[]{"", "FarmersDelight/"}) {
            Path path = Path.of(prefix + relative);
            if (Files.isRegularFile(path)) return Files.readString(path);
        }
        throw new IOException("resource not found: " + relative);
    }
}
