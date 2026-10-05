package com.huidu.farmersdelight.pack;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * World generation and client names for the wild plants: each shipped wild plant needs its placed feature, and
 * every skewer item name has to resolve in every locale the pack ships (upstream only translates the old
 * barbecue_stick id, so the four skewer keys fall back to the en_us value elsewhere).
 */
class WildPlantWorldgenTest {

    private static final Path CFG = Path.of(
            "src/main/resources/craftengine/farmersdelight/configuration");
    private static final Path LANG = Path.of(
            "src/main/resources/craftengine/farmersdelight/resourcepack/assets/farmersdelight/lang");

    @Test
    void everyWildPlantHasAPlacedFeature() throws Exception {
        YamlConfiguration wild = YamlConfiguration.loadConfiguration(CFG.resolve("wild_plants.yml").toFile());
        ConfigurationSection placed = wild.getConfigurationSection("placed-features");
        assertTrue(placed != null, "wild_plants.yml must keep its placed-features section");
        List<String> plants = new ArrayList<>();
        for (String id : wild.getConfigurationSection("blocks").getKeys(false)) {
            String shortName = id.substring(id.indexOf(':') + 1);
            if (shortName.startsWith("wild_")) {
                plants.add(id);
            }
        }
        assertTrue(plants.size() >= 4, "expected the four wild plants, got " + plants);
        for (String id : plants) {
            ConfigurationSection feature = placed.getConfigurationSection(id);
            assertTrue(feature != null, id + " has no placed feature (upstream ships one per wild plant)");
            assertFalse(feature.getStringList("biome").isEmpty(),
                    id + "'s placed feature must name its biomes");
        }
    }

    @Test
    void everySkewerItemNameResolvesInEveryLocale() throws Exception {
        // Bukkit splits keys on dots, so read the quoted keys out of the file text instead.
        Set<String> skewers = new LinkedHashSet<>();
        java.util.regex.Matcher keys = java.util.regex.Pattern
                .compile("\"(item\\.farmersdelight\\.[a-z_]*skewer[a-z_]*)\"")
                .matcher(Files.readString(LANG.resolve("en_us.json")));
        while (keys.find()) {
            skewers.add(keys.group(1));
        }
        assertTrue(skewers.size() >= 4, "expected the four skewer item keys, got " + skewers);
        List<String> missing = new ArrayList<>();
        try (var files = Files.list(LANG)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".json")).toList()) {
                YamlConfiguration locale = YamlConfiguration.loadConfiguration(file.toFile());
                for (String key : skewers) {
                    if (locale.getString(key, "").isEmpty()) {
                        missing.add(file.getFileName() + ":" + key);
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(), "skewer item names missing from shipped locales: " + missing);
    }
}
