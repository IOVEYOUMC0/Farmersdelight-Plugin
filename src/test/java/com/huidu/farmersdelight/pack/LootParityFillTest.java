package com.huidu.farmersdelight.pack;

import com.huidu.farmersdelight.util.Constants;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the knife interactions the bundled pack declares in step with upstream Farmer's Delight 1.4: the
 * pumpkin slice replacement, the cake slicing that scales with the remaining bites, the sandy shrub straw,
 * and the knife test itself.
 */
class LootParityFillTest {

    private static final Path CFG = Path.of(
            "src/main/resources/craftengine/farmersdelight/configuration");
    private static final Path LOOTS = CFG.resolve("vanilla_loots.yml");
    private static final Path CROPS = CFG.resolve("crops.yml");
    private static final Path FOOD_BLOCKS = CFG.resolve("food_block.yml");

    @Test
    void knifeOnPumpkinReplacesTheDropWithFourSlices() {
        ConfigurationSection rule = source("farmersdelight:pumpkin_slices_from_knife");

        assertEquals("block", rule.getString("type"));
        assertEquals("minecraft:pumpkin", rule.getString("target"));
        assertEquals("items", rule.getString("overwrite"),
                "the replacement only applies when the knife rule matches, so the source must overwrite");
        assertEquals(List.of(Constants.CONDITION_IS_KNIFE, "inverted"), sourceConditionTypes(rule));

        Map<?, ?> silkTouch = firstOf(rule.getMapList("conditions").get(1).get("terms"));
        assertEquals("enchantment", silkTouch.get("type"));
        assertTrue(String.valueOf(silkTouch.get("predicate")).contains("silk_touch"),
                "a silk touch knife keeps the whole pumpkin");

        Map<?, ?> entry = firstPoolEntry(rule, 0);
        assertEquals("farmersdelight:pumpkin_slice", entry.get("item"));
        assertEquals(4, ((Number) entry.get("count")).intValue());
    }

    @Test
    void knifeOnCakeDropsOneSlicePerRemainingBite() {
        ConfigurationSection rule = source("farmersdelight:cake_slices_from_knife");

        assertEquals("minecraft:cake", rule.getString("target"));
        assertEquals("items", rule.getString("overwrite"));
        assertEquals(List.of(Constants.CONDITION_IS_KNIFE), sourceConditionTypes(rule));

        List<Map<?, ?>> children = list(firstPoolEntry(rule, 0).get("children"));
        assertEquals(7, children.size(), "a cake has bites 0..6");
        for (int bites = 0; bites <= 6; bites++) {
            Map<?, ?> child = children.get(bites);
            assertEquals("farmersdelight:cake_slice", child.get("item"));
            assertEquals(7 - bites, ((Number) child.get("count")).intValue(),
                    "seven slices for an untouched cake, one for the last bite");
            Map<?, ?> property = firstOf(child.get("conditions"));
            assertEquals("match_block_property", property.get("type"));
            assertEquals(String.valueOf(bites), map(property.get("properties")).get("bites"));
        }
    }

    @Test
    void everyKnifeRuleUsesTheKnifeSetInsteadOfAnIdRegex() throws Exception {
        for (Path file : List.of(LOOTS, CROPS, FOOD_BLOCKS)) {
            assertFalse(Files.readString(file).contains(".*_knife"),
                    file + " must identify knives through " + Constants.CONDITION_IS_KNIFE
                            + ", which also sees knives outside the farmersdelight namespace");
        }
        for (String id : List.of("straw_from_short_grass", "straw_from_tall_grass",
                "straw_from_mature_wheat")) {
            assertTrue(conditionTypes(source("farmersdelight:" + id), 0).contains(Constants.CONDITION_IS_KNIFE),
                    id + " must test the knife set");
        }
    }

    @Test
    void candleCakeKeepsItsVanillaDrop() throws Exception {
        assertFalse(Files.readString(LOOTS).contains("minecraft:candle_cake"),
                "upstream slicing only reaches cake and pie block states, so the candle cake keeps dropping "
                        + "its candle");
    }

    private static ConfigurationSection source(String id) {
        assertTrue(Files.exists(LOOTS), LOOTS.toString());
        ConfigurationSection section = YamlConfiguration.loadConfiguration(LOOTS.toFile())
                .getConfigurationSection("vanilla_loots." + id);
        assertNotNull(section, id);
        return section;
    }

    private static List<Map<?, ?>> pools(ConfigurationSection section) {
        return section.getMapList("loot.pools");
    }

    private static Map<?, ?> firstPoolEntry(ConfigurationSection section, int pool) {
        return list(pools(section).get(pool).get("entries")).get(0);
    }

    private static Map<?, ?> firstPoolCondition(ConfigurationSection section, int pool, int index) {
        return list(pools(section).get(pool).get("conditions")).get(index);
    }

    /**
     * The condition types a loot source carries at the source level. A rule that replaces the vanilla drop
     * has to put its condition here: CraftEngine reads the overwrite flag from a source only after that
     * source matched, so a pool-level condition would let the replacement fire for every break.
     */
    private static List<String> sourceConditionTypes(ConfigurationSection section) {
        List<String> types = new ArrayList<>();
        for (Map<?, ?> condition : section.getMapList("conditions")) {
            types.add(String.valueOf(condition.get("type")));
        }
        return List.copyOf(new LinkedHashSet<>(types));
    }

    /** The condition types of one pool, where an additive rule such as the straw bonus keeps its own. */
    private static List<String> conditionTypes(ConfigurationSection section, int pool) {
        List<String> types = new ArrayList<>();
        for (Map<?, ?> condition : list(pools(section).get(pool).get("conditions"))) {
            types.add(String.valueOf(condition.get("type")));
        }
        return List.copyOf(new LinkedHashSet<>(types));
    }

    private static Map<?, ?> firstOf(Object terms) {
        return list(terms).get(0);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> list(Object node) {
        return node == null ? List.of() : (List<Map<?, ?>>) node;
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> map(Object node) {
        return node == null ? Map.of() : (Map<?, ?>) node;
    }
}
