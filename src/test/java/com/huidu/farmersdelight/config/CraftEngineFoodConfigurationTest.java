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

    /**
     * Pins the Farmer's Delight 1.4 food values, which are absolute values in that release (the old
     * nutrition x modifier x 2 numbers are gone). Read from the shipped YAML rather than from CraftEngine's
     * parsed food component, because building an ItemStack needs a running server: the numbers below are
     * exactly the fields CraftEngine feeds into the food component.
     */
    @Test
    void foodValuesMatchTheFourteenAbsoluteValues() {
        Path path = Path.of("src", "main", "resources", "craftengine", "farmersdelight",
                "configuration", "items.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(path.toFile());

        Map<String, List<Number>> expected = Map.ofEntries(
                Map.entry("cabbage", List.of(4, 2)),
                Map.entry("tomato", List.of(1, 1)),
                Map.entry("onion", List.of(2, 1)),
                Map.entry("cabbage_leaf", List.of(2, 1)),
                Map.entry("fried_egg", List.of(4, 3.5)),
                Map.entry("tomato_sauce", List.of(4, 3.5)),
                Map.entry("wheat_dough", List.of(2, 1.5)),
                Map.entry("raw_pasta", List.of(2, 1.5)),
                Map.entry("pie_crust", List.of(2, 1)),
                Map.entry("pumpkin_slice", List.of(3, 2)),
                // 1.4 gave this cut a smaller modifier than the other cuts (FoodValues:unsafeFood, 0.15).
                Map.entry("chicken_cuts", List.of(1, 0.3)),
                Map.entry("cake_slice", List.of(3, 3)),
                Map.entry("melon_popsicle", List.of(3, 1.5)),
                Map.entry("glow_berry_custard", List.of(8, 9)),
                Map.entry("fruit_salad", List.of(8, 10)),
                Map.entry("mixed_salad", List.of(8, 10)),
                Map.entry("dog_food", List.of(4, 2)),
                Map.entry("cooked_rice", List.of(6, 5)),
                Map.entry("bone_broth", List.of(8, 10)),
                // Crude meals (tier 1) were already at the 1.4 values; the tiers below moved.
                Map.entry("bacon_and_eggs", List.of(10, 12)),
                Map.entry("beef_stew", List.of(12, 16)),
                Map.entry("fried_rice", List.of(12, 16)),
                Map.entry("steak_and_potatoes", List.of(12, 16)),
                // Grilled Salmon was demoted from the fancy tier to the hearty tier.
                Map.entry("grilled_salmon", List.of(12, 16)),
                Map.entry("pumpkin_soup", List.of(14, 20)),
                Map.entry("roasted_mutton_chops", List.of(14, 20)),
                Map.entry("squid_ink_pasta", List.of(14, 20)),
                Map.entry("roast_chicken", List.of(14, 20)),
                Map.entry("gleaming_salad", List.of(14, 20)),
                // Slices are 5/5 with the pumpkin one excepted; the pack has no pumpkin_pie_slice item yet.
                Map.entry("pie_slice_template.peach", List.of(5, 5)),
                // Already at their 1.4 values before this change: kept as a regression anchor.
                Map.entry("salmon_slice", List.of(1, 0.2)),
                Map.entry("cod_slice", List.of(1, 0.2)),
                Map.entry("cooked_mutton_chops", List.of(3, 4.8)),
                Map.entry("smoked_ham", List.of(10, 16)));

        for (Map.Entry<String, List<Number>> entry : expected.entrySet()) {
            String id = entry.getKey();
            String foodPath = id.equals("pie_slice_template.peach")
                    ? "templates.farmersdelight:pie_slice_template.data.food"
                    : "items.farmersdelight:" + id + ".data.food";
            if (id.equals("pie_slice_template.peach")) {
                // The template is not an item; only its two numbers are pinned.
                assertEquals(5.0, yaml.getDouble(foodPath + ".nutrition"), 0.0, id + " nutrition");
                assertEquals(5.0, yaml.getDouble(foodPath + ".saturation"), 0.0, id + " saturation");
                continue;
            }
            double nutrition = yaml.getDouble(foodPath + ".nutrition", -1.0);
            double saturation = yaml.getDouble(foodPath + ".saturation", -1.0);
            assertEquals(entry.getValue().get(0).doubleValue(), nutrition, 0.0,
                    id + " nutrition (1.4 absolute)");
            assertEquals(entry.getValue().get(1).doubleValue(), saturation, 0.0,
                    id + " saturation (1.4 absolute)");
        }
    }
}
