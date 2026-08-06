package com.huidu.farmersdelight.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftEngineFoodConfigurationTest {

    private static final Map<String, Integer> NOURISHMENT_FOODS = Map.ofEntries(
            Map.entry("farmersdelight:cooked_rice", 30),
            Map.entry("farmersdelight:bone_broth", 60),
            Map.entry("farmersdelight:bacon_and_eggs", 60),
            Map.entry("farmersdelight:ratatouille", 60),
            Map.entry("farmersdelight:beef_stew", 180),
            Map.entry("farmersdelight:vegetable_soup", 180),
            Map.entry("farmersdelight:fish_stew", 180),
            Map.entry("farmersdelight:onion_soup", 180),
            Map.entry("farmersdelight:steak_and_potatoes", 180),
            Map.entry("farmersdelight:pasta_with_meatballs", 180),
            Map.entry("farmersdelight:pasta_with_mutton_chop", 180),
            Map.entry("farmersdelight:mushroom_rice", 180),
            Map.entry("farmersdelight:grilled_salmon", 180),
            Map.entry("farmersdelight:chicken_soup", 180),
            Map.entry("farmersdelight:fried_rice", 180),
            Map.entry("minecraft:mushroom_stew", 180),
            Map.entry("minecraft:beetroot_soup", 180),
            Map.entry("farmersdelight:pumpkin_soup", 300),
            Map.entry("farmersdelight:baked_cod_stew", 300),
            Map.entry("farmersdelight:noodle_soup", 300),
            Map.entry("farmersdelight:roasted_mutton_chops", 300),
            Map.entry("farmersdelight:vegetable_noodles", 300),
            Map.entry("farmersdelight:squid_ink_pasta", 300),
            Map.entry("farmersdelight:roast_chicken", 300),
            Map.entry("farmersdelight:stuffed_pumpkin", 300),
            Map.entry("farmersdelight:honey_glazed_ham", 300),
            Map.entry("farmersdelight:shepherds_pie", 300),
            Map.entry("farmersdelight:gleaming_salad", 300),
            Map.entry("minecraft:rabbit_stew", 300)
    );

    @Test
    void builtInPetFoodsAndBuffsLiveInCraftEngineItems() {
        Path path = Path.of("src", "main", "resources", "craftengine", "farmersdelight",
                "configuration", "items.yml");
        assertTrue(Files.exists(path));
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(path.toFile());

        for (Map.Entry<String, Integer> entry : NOURISHMENT_FOODS.entrySet()) {
            String itemPath = "items." + entry.getKey() + ".template";
            List<String> templates = yaml.isList(itemPath)
                    ? yaml.getStringList(itemPath)
                    : List.of(yaml.getString(itemPath, ""));
            assertTrue(
                    templates.contains("farmersdelight:nourishment_" + entry.getValue() + "_template"),
                    entry.getKey() + " is missing its Nourishment template"
            );
        }

        for (int duration : List.of(30, 60, 180, 300)) {
            String functionPath = "templates.farmersdelight:nourishment_" + duration
                    + "_template.events";
            List<Map<?, ?>> events = yaml.getMapList(functionPath);
            assertEquals(1, events.size());
            assertTrue(events.getFirst().containsKey("functions"));
            List<?> functions = (List<?>) events.getFirst().get("functions");
            assertEquals(1, functions.size());
            Map<?, ?> function = (Map<?, ?>) functions.getFirst();
            assertEquals("farmersdelight:nourishment", function.get("type"));
            assertEquals(duration, ((Number) function.get("duration")).intValue());
        }

        ConfigurationSection dogFood = yaml.getConfigurationSection(
                "items.farmersdelight:dog_food.settings.farmersdelight:pet_food");
        ConfigurationSection horseFeed = yaml.getConfigurationSection(
                "items.farmersdelight:horse_feed.settings.farmersdelight:pet_food");
        assertNotNull(dogFood);
        assertNotNull(horseFeed);
        assertEquals(List.of("WOLF"), dogFood.getStringList("entities"));
        assertEquals(2, dogFood.getMapList("effects").size());
        assertTrue(horseFeed.getBoolean("tempt.enabled"));
    }

    @Test
    void mainConfigNoLongerOwnsPetFoodsOrFoodAssignments() {
        Path path = Path.of("src", "main", "resources", "config.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(path.toFile());

        assertFalse(yaml.isSet("pet-foods"));
        assertFalse(yaml.isSet("buff.comfort.foods"));
        assertFalse(yaml.isSet("buff.nourishment.foods"));
    }

    @Test
    void rottenTomatoUsesTheVanillaSnowballProjectileRenderer() {
        Path path = Path.of("src", "main", "resources", "craftengine", "farmersdelight",
                "configuration", "items.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(path.toFile());
        String itemPath = "items.farmersdelight:rotten_tomato";

        assertEquals("snowball", yaml.getString(itemPath + ".material"));
        assertFalse(yaml.isSet(itemPath + ".settings.projectile"));
    }
}
