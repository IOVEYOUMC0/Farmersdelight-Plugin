package com.huidu.farmersdelight.pack;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards that every item the rich-soil behaviour lists as unaffected actually exists in the pack, so a
 * configuration edit cannot leave a dangling reference behind.
 */
class ParityContentFillTest {

    private static final Path CFG = Path.of(
            "src/main/resources/craftengine/farmersdelight/configuration");
    private static final Path LANG = Path.of(
            "src/main/resources/craftengine/farmersdelight/resourcepack/assets/farmersdelight/lang");
    /** Block ids are defined under any of these sections across the shipped pack files. */
    private static boolean isDefinedAnywhere(String id) throws Exception {
        for (Path file : Files.list(CFG).filter(path -> path.toString().endsWith(".yml")).toList()) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file.toFile());
            for (String section : List.of("items", "blocks", "block", "templates", "furniture")) {
                ConfigurationSection root = config.getConfigurationSection(section);
                if (root != null && root.getKeys(false).contains(id)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void everyRichSoilExceptionIdIsDefinedSomewhere() throws Exception {
        YamlConfiguration blocks = YamlConfiguration.loadConfiguration(CFG.resolve("blocks.yml").toFile());
        List<Map<?, ?>> behaviours = blocks.getMapList("block.farmersdelight:rich_soil.behavior");
        assertFalse(behaviours.isEmpty(), "the rich-soil behaviour must still exist");
        List<?> exceptions = (List<?>) behaviours.get(0).get("unaffected-blocks");
        assertTrue(exceptions != null && !exceptions.isEmpty(), "the rich-soil exception list must still exist");
        for (Object entry : exceptions) {
            String id = String.valueOf(entry);
            if (!id.startsWith("farmersdelight:")) {
                continue;
            }
            assertTrue(isDefinedAnywhere(id),
                    id + " is referenced by the rich-soil exception list but never defined");
        }
    }

            @Test
    void theRopeTomatoKeepsItsAgeChainAndStaysOutOfRopeConnectionConfig() throws Exception {
        YamlConfiguration crops = YamlConfiguration.loadConfiguration(CFG.resolve("crops.yml").toFile());
        ConfigurationSection vine = crops.getConfigurationSection("blocks.farmersdelight:tomato_crop_on_rope");
        assertTrue(vine != null, "the rope tomato is shipped as farmersdelight:tomato_crop_on_rope");
        ConfigurationSection appearances = vine.getConfigurationSection("states.appearances");
        for (int age = 0; age <= 3; age++) {
            assertTrue(appearances.getConfigurationSection("age=" + age) != null,
                    "the rope tomato needs its age=" + age + " stage (upstream age 0..3)");
        }
        assertTrue(Files.readString(CFG.resolve("crops.yml")).contains("farmersdelight:tomato_vine"),
                "the rope tomato must keep the tomato_vine behavior");
        assertEqualsString("farmersdelight:tomato_seeds", vine.getString("settings.overrides.item"),
                "the rope tomato's pick item stays tomato seeds");
        String cropsText = Files.readString(CFG.resolve("crops.yml"));
        assertTrue(cropsText.contains("item: farmersdelight:tomato"),
                "the ripe rope tomato must drop tomatoes");
        assertFalse(Files.readString(CFG.resolve("blocks.yml")).contains("tomato_crop_on_rope"),
                "the vine must stay out of blocks.yml, where the rope block's connection configuration lives, "
                        + "so rope ties, climbing and the bell scan are untouched");
    }

    private static void assertEqualsString(String expected, String actual, String message) {
        assertTrue(expected.equals(actual), message + ": expected " + expected + " but was " + actual);
    }
}
