package com.huidu.farmersdelight.handheld;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Barbeque's Delight plugs into FarmersDelight's handheld skewer cooking through a plain vanilla campfire
 * recipe per raw skewer: FarmersDelight's gate is result-driven (HandCookedSkewerHooks ->
 * CampfireSkewerResults -> CampfireRecipeCache.find, which reads Bukkit campfire recipes), so the addon needs
 * no FarmersDelight API and FarmersDelight ships no addon id at all.
 */
class HandheldSkewerIntegrationTest {

    private static final Path FD_RESOURCES = Path.of("src/main/resources");
    private static final Path BBQD = Path.of(
            "../BarbequesDelight/src/main/resources/craftengine/barbequesdelight/configuration/recipes");
    private static final Path GRILLING = BBQD.resolve("grilling_recipes.yml");
    private static final Path CAMPFIRE = BBQD.resolve("campfire_recipes.yml");

    @Test
    void rawSkewersDeclareTheirOwnHandheldTagAndPoseModels() {
        for (String namespace : List.of("farmersdelight", "barbequesdelight")) {
            boolean addon = !namespace.equals("farmersdelight");
            if (addon && !Files.isDirectory(BBQD.getParent())) {
                // A standalone checkout (the plugin's own CI) has no sibling addon repositories; the
                // workspace checkout runs this branch.
                continue;
            }
            Path folder = addon ? BBQD.getParent() : FD_RESOURCES.resolve("craftengine/farmersdelight/configuration");
            var items = YamlConfiguration.loadConfiguration(folder.resolve("items.yml").toFile());
            var poses = YamlConfiguration.loadConfiguration(folder.resolve("handheld_skewers.yml").toFile());
            int tagged = 0;
            for (String id : items.getConfigurationSection("items").getKeys(false)) {
                List<String> tags = items.getStringList("items." + id + ".settings.tags");
                if (!tags.contains(CampfireSkewerResults.HAND_COOKABLE_TAG)) continue;
                tagged++;
                assertTrue(id.contains("skewer") && !id.contains("cooked") && !id.contains("grilled"), id);
                String pose = "items." + id + "_hand_cooking_display";
                assertTrue(poses.contains(pose), id + " needs its pose model");
                assertTrue(id.endsWith(":" + poses.getString(pose + ".arguments.skewer")), id);
            }
            assertTrue(tagged == (namespace.equals("farmersdelight") ? 2 : 10), namespace + ": " + tagged);
        }
    }

    @Test
    void everyRawSkewerHasACampfireRecipeMatchingItsGrillingResult() throws Exception {
        if (!Files.isRegularFile(GRILLING) || !Files.isRegularFile(CAMPFIRE)) {
            // The addon's recipe files only exist in a workspace checkout; the plugin's own CI has none.
            return;
        }
        YamlConfiguration grilling = YamlConfiguration.loadConfiguration(GRILLING.toFile());
        YamlConfiguration campfire = YamlConfiguration.loadConfiguration(CAMPFIRE.toFile());
        ConfigurationSection raw = grilling.getConfigurationSection("grilling_recipes");
        ConfigurationSection cooked = campfire.getConfigurationSection("recipes");
        assertFalse(raw == null || cooked == null, "both addon recipe files must define their root section");

        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (String key : raw.getKeys(false)) {
            String input = raw.getString(key + ".input");
            String result = raw.getString(key + ".result");
            int grillTime = raw.getInt(key + ".grill-time");
            String match = null;
            for (String recipeId : cooked.getKeys(false)) {
                if (input.equals(cooked.getString(recipeId + ".ingredient"))) {
                    match = recipeId;
                    break;
                }
            }
            if (match == null) {
                problems.add(input + " has no campfire recipe");
                continue;
            }
            checked++;
            if (!"campfire_cooking".equals(cooked.getString(match + ".type"))) {
                problems.add(match + " must be a campfire_cooking recipe");
            }
            if (!result.equals(cooked.getString(match + ".result.id"))) {
                problems.add(match + " must yield " + result);
            }
            if (cooked.getInt(match + ".time") != grillTime) {
                problems.add(match + " must keep the grilling time " + grillTime);
            }
        }
        assertTrue(checked >= 10, "expected the ten raw skewers, resolved " + checked);
        assertTrue(problems.isEmpty(), "campfire recipes out of step with grilling: " + problems);
    }

    @Test
    void theHandheldSkewerPathShipsNoAddonIds() throws Exception {
        List<String> offenders = new ArrayList<>();
        List<Path> owned = new ArrayList<>();
        try (var files = Files.walk(Path.of("src/main/java/com/huidu/farmersdelight/handheld"))) {
            files.filter(Files::isRegularFile).forEach(owned::add);
        }
        owned.add(Path.of("src/main/resources/craftengine/farmersdelight/configuration/recipes.yml"));
        owned.add(Path.of("src/main/resources/config.yml"));
        for (Path file : owned) {
            String text = Files.readString(file);
            for (String addon : List.of("barbequesdelight", "crabbersdelight", "brewinandchewin",
                    "endsdelight", "villagersdelight")) {
                if (text.contains(addon)) {
                    offenders.add(file + " mentions " + addon);
                    break;
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "the handheld skewer path must stay result-driven and addon-agnostic: " + offenders);
    }
}
