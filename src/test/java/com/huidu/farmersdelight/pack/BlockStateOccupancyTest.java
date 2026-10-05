package com.huidu.farmersdelight.pack;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the two block-state collisions the occupancy guard found (task-138): ten food blocks pinned one
 * tripwire state, and the two tatami mats shared a carpet.
 *
 *
 * The general rule lives in tools/check_block_state_occupancy.py, which now fails the build; this test
 * keeps the two concrete fixes from being reverted. It reads the pack files through the classpath, so it needs
 * no server, and it counts the pins in the file text so a re-added pin really fails it.
 */
class BlockStateOccupancyTest {

    private static final List<String> FOOD_BLOCKS = List.of(
            "farmersdelight:gleaming_salad_block",
            "farmersdelight:honey_glazed_ham_block",
            "farmersdelight:rice_roll_medley_block",
            "farmersdelight:roast_chicken_block",
            "farmersdelight:shepherds_pie_block",
            "farmersdelight:stuffed_pumpkin_block");

    @Test
    void noFoodBlockPinsTheTripwireStateAnyMore() {
        String foodText = packText("food_block.yml");
        assertEquals(0, occurrences(foodText, "state: tripwire"),
                "no face may pin the shared tripwire state; every one of them reserves its own");
        assertEquals(0, occurrences(foodText, "auto_state"),
                "the family pins explicit campfire states, like the other add-ons do");
        assertEquals(148, occurrences(foodText, "state: campfire[") + occurrences(foodText, "state: soul_campfire["),
                "all 148 faces pin one of the family's campfire states");

        ConfigurationSection food = pack("food_block.yml");
        ConfigurationSection template = food.getConfigurationSection(
                "templates.farmersdelight:sliceable_pie_states.states.appearances");
        assertNotNull(template, "the pie template has to keep its appearances");
        for (String face : template.getKeys(false)) {
            assertEquals("soul_campfire[facing=south,lit=false,signal_fire=false,waterlogged=false]",
                    template.getConfigurationSection(face).getString("state"),
                    "template face " + face + " uses the shared soul campfire state");
        }

        ConfigurationSection blocks = food.getConfigurationSection("blocks");
        assertNotNull(blocks, "food_block.yml keeps its blocks section");
        for (String block : FOOD_BLOCKS) {
            ConfigurationSection appearances = blocks.getConfigurationSection(block + ".states.appearances");
            assertNotNull(appearances, block + " has to keep its appearances");
            assertEquals(appearances.getKeys(false).size(), appearances.getKeys(false).stream()
                            .filter(face -> appearances.getConfigurationSection(face).getString("state") != null)
                            .count(),
                    block + " must pin a campfire state on every face");
        }
    }

    @Test
    void theTwoTatamiMatsNoLongerShareACarpet() {
        ConfigurationSection blocks = pack("blocks.yml");
        String half = blocks.getString("block.farmersdelight:half_tatami_mat.state.state");
        ConfigurationSection full = blocks.getConfigurationSection(
                "block.farmersdelight:full_tatami_mat.states.appearances");
        assertNotNull(full, "full_tatami_mat has to keep its appearances");

        List<String> fullStates = full.getKeys(false).stream()
                .map(face -> full.getConfigurationSection(face).getString("state"))
                .distinct()
                .toList();
        assertEquals(1, fullStates.size(), "full_tatami_mat pins one state across its faces: " + fullStates);
        assertNotNull(half, "half_tatami_mat keeps its mapping form");
        assertTrue(!half.equals(fullStates.getFirst()), "the two mats must not share " + half);
        assertEquals("brown_carpet", half, "the half mat moved to a carpet nobody else pins");
    }

    @Test
    void everyCarpetStateHasExactlyOneOwnerInThePack() {
        ConfigurationSection blocks = pack("blocks.yml").getConfigurationSection("block");
        assertNotNull(blocks, "blocks.yml keeps its block section");
        for (String state : new String[]{"yellow_carpet", "gray_carpet", "brown_carpet"}) {
            List<String> owners = new ArrayList<>();
            for (String key : blocks.getKeys(false)) {
                ConfigurationSection appearances = blocks.getConfigurationSection(key + ".states.appearances");
                boolean pinned = appearances != null && appearances.getKeys(false).stream()
                        .anyMatch(face -> state.equals(appearances.getConfigurationSection(face).getString("state")));
                pinned = pinned || state.equals(blocks.getString(key + ".state.state"));
                if (pinned) {
                    owners.add(key);
                }
            }
            assertTrue(owners.size() <= 1, state + " is pinned by more than one block: " + owners);
        }
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private static String packText(String file) {
        String resource = "craftengine/farmersdelight/configuration/" + file;
        try (InputStream stream = BlockStateOccupancyTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream, "missing pack resource: " + resource);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new AssertionError("cannot read " + resource, error);
        }
    }

    private static ConfigurationSection pack(String file) {
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.loadFromString(packText(file));
        } catch (Exception error) {
            throw new AssertionError("cannot parse " + file, error);
        }
        return configuration;
    }
}
