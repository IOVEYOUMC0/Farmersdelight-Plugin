package com.huidu.farmersdelight.pack;

import com.huidu.farmersdelight.util.Constants;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a broken basket hands over, and how its items reach their blocks.
 *
 *
 * Sharing states changed no id, no drop and no item: the legacy basket block still breaks into the bamboo
 * basket, the bamboo and wooden baskets still drop themselves through the shared loot template, and every item
 * still places the block of its own id. The basket and the bamboo basket reference the same states, so the two
 * blocks a player sees are the same basket under two ids; the wooden basket keeps its own states and looks.
 *
 *
 * Data only: the pack files are read through the classpath, no server is involved. What a real break does with
 * the container's contents is on the real-server checklist, not asserted here.
 */
class BasketDropTest {

    private static final String BASKET = "farmersdelight:basket";
    private static final String BAMBOO = "farmersdelight:bamboo_basket";
    private static final String WOODEN = "farmersdelight:wooden_basket";

    @Test
    void theLegacyBasketBlockDropsTheBambooBasket() {
        ConfigurationSection block = blocks().getConfigurationSection("block." + BASKET);
        assertNotNull(block, "the legacy block definition has to stay: old worlds still contain it");

        // Bukkit does not index into YAML lists through a path, so the pools are read as maps.
        List<Map<?, ?>> pools = block.getMapList("loot.pools");
        assertEquals(1, pools.size(), "one pool, like the other explicit loot pools in this pack");
        Map<?, ?> pool = pools.getFirst();
        assertEquals(1, pool.get("rolls"), "one basket in, one basket out");
        assertEquals("survives_explosion", firstEntry(pool, "conditions").get("type"),
                "same condition as the other explicit loot pools in this pack");
        assertEquals(BAMBOO, firstEntry(pool, "entries").get("item"),
                "breaking a legacy basket has to hand over the 1.4 item");
        assertFalse(block.getStringList("template").contains("farmersdelight:block_loot_template"),
                "the self-drop template must not come back, or the block would drop the legacy id again");
    }

    @Test
    void theTwoNewBasketsStillDropThemselves() {
        ConfigurationSection blocks = blocks().getConfigurationSection("block");
        for (String name : new String[]{"bamboo_basket", "wooden_basket"}) {
            ConfigurationSection block = blocks.getConfigurationSection("farmersdelight:" + name);
            assertNotNull(block, name + " has to keep its own block definition");
            assertTrue(block.getStringList("template").contains("farmersdelight:block_loot_template"),
                    name + " keeps dropSelf via the shared loot template");
            assertNull(block.get("loot"), name + " must not take the legacy basket's explicit loot");
        }
    }

    @Test
    void theBambooItemPlacesTheSharedBlockWithoutABlockState() {
        ConfigurationSection item = item(BAMBOO);
        assertEquals("farmersdelight:block_item_template", item.getString("template"),
                BAMBOO + " keeps the shared block-item template");
        assertEquals("${__NAMESPACE__}:${__ID__}",
                blocks().getString("templates.farmersdelight:block_item_template.behavior.block"),
                "the template places the block named after the item id, so this item places its own block");
        assertNull(item.get("data.block_state"),
                "it writes no block state: its block references the same states as the basket block");
    }

    @Test
    void theLegacyItemAndBlockDefinitionsStayAsMigrationCarriers() {
        assertNotNull(blocks().getConfigurationSection("block." + BASKET),
                "the legacy block stays: removing it would turn placed baskets into air");
        assertEquals(BASKET, Constants.BEHAVIOR_BASKET,
                "the behaviour id both basket blocks list is still the legacy block id");
        List<String> producers = new ArrayList<>();
        for (ConfigurationSection configuration : recipeFiles()) {
            ConfigurationSection recipes = configuration.getConfigurationSection("recipes");
            if (recipes == null) {
                continue;
            }
            for (String key : recipes.getKeys(false)) {
                if (BASKET.equals(recipes.getString(key + ".result.id"))) {
                    producers.add(key);
                }
            }
        }
        assertEquals(List.of(), producers, "no recipe may craft the carrier id: " + producers);
    }

    private static ConfigurationSection item(String id) {
        ConfigurationSection items = blocks().getConfigurationSection("items");
        assertNotNull(items, "blocks.yml keeps its items section");
        ConfigurationSection item = items.getConfigurationSection(id);
        assertNotNull(item, id + " has to keep its item definition");
        return item;
    }

    /** The first entry of a loot pool list, as a map; the lists here always hold one element. */
    private static Map<?, ?> firstEntry(Map<?, ?> pool, String key) {
        Object value = pool.get(key);
        assertTrue(value instanceof List<?>, key + " has to be a list, was " + value);
        List<?> list = (List<?>) value;
        assertEquals(1, list.size(), key + " holds exactly one element in this fixture");
        Object first = list.getFirst();
        assertTrue(first instanceof Map<?, ?>, key + " entries are maps, was " + first);
        return (Map<?, ?>) first;
    }

    private static ConfigurationSection blocks() {
        String resource = "craftengine/farmersdelight/configuration/blocks.yml";
        try (InputStream stream = BasketDropTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream, "missing pack resource: " + resource);
            YamlConfiguration configuration = new YamlConfiguration();
            configuration.loadFromString(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            return configuration;
        } catch (Exception error) {
            throw new AssertionError("cannot read " + resource, error);
        }
    }

    /** Every configuration file that holds pack recipes; the recipes no longer sit inside blocks.yml. */
    private static List<ConfigurationSection> recipeFiles() {
        Path directory = Path.of("src", "main", "resources", "craftengine", "farmersdelight", "configuration");
        try (Stream<Path> files = Files.list(directory)) {
            List<ConfigurationSection> sections = new ArrayList<>();
            for (Path path : files.filter(file -> file.getFileName().toString().endsWith(".yml"))
                    .sorted().toList()) {
                String resource = "craftengine/farmersdelight/configuration/" + path.getFileName();
                try (InputStream stream = BasketDropTest.class.getClassLoader().getResourceAsStream(resource)) {
                    assertNotNull(stream, "missing pack resource: " + resource);
                    YamlConfiguration configuration = new YamlConfiguration();
                    configuration.loadFromString(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
                    if (configuration.isConfigurationSection("recipes")) {
                        sections.add(configuration);
                    }
                }
            }
            assertFalse(sections.isEmpty(), "no configuration file carries the recipes root key");
            return sections;
        } catch (Exception error) {
            throw new AssertionError("cannot list " + directory, error);
        }
    }
}
