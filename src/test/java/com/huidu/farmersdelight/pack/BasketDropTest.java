package com.huidu.farmersdelight.pack;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The legacy {@code basket} block keeps its definition (old worlds still hold it) but breaks into the 1.4 item:
 * a placed basket drops a bamboo basket, so a player who removes one ends up with the current id even before
 * the migration layer sees anything. The two new baskets are untouched and still drop themselves.
 *
 *
 * Data only: the pack files are read through the classpath, no server is involved. What a real break does with
 * the container's contents is on the real-server checklist, not asserted here.
 */
class BasketDropTest {

    private static final String LEGACY = "farmersdelight:basket";
    private static final String BAMBOO = "farmersdelight:bamboo_basket";

    @Test
    void theLegacyBasketBlockDropsTheBambooBasket() {
        ConfigurationSection block = blocks().getConfigurationSection("block." + LEGACY);
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
                "the self-drop template must be gone, or the block would drop the removed id again");
    }

    @Test
    void theTwoNewBasketsStillDropThemselves() {
        ConfigurationSection blocks = blocks().getConfigurationSection("block");
        for (String name : new String[]{"bamboo_basket", "wooden_basket"}) {
            ConfigurationSection block = blocks.getConfigurationSection("farmersdelight:" + name);
            assertNotNull(block, name + " has to keep its block definition");
            assertTrue(block.getStringList("template").contains("farmersdelight:block_loot_template"),
                    name + " keeps dropSelf via the shared loot template");
            assertNull(block.get("loot"), name + " must not get the legacy basket's explicit loot");
        }
    }

    @Test
    void theLegacyItemAndBlockDefinitionsStayAsMigrationCarriers() {
        ConfigurationSection pack = blocks();
        assertNotNull(pack.getConfigurationSection("items." + LEGACY),
                "the legacy item stays: it is the migration carrier for stacks already in the world");
        assertNotNull(pack.getConfigurationSection("block." + LEGACY),
                "the legacy block stays: removing it would turn placed baskets into air");
        ConfigurationSection recipes = pack.getConfigurationSection("recipes");
        assertNotNull(recipes, "blocks.yml keeps its recipes section");
        List<String> producers = new ArrayList<>();
        for (String key : recipes.getKeys(false)) {
            if (LEGACY.equals(recipes.getString(key + ".result.id"))) {
                producers.add(key);
            }
        }
        assertEquals(List.of(), producers, "no recipe may craft the removed id: " + producers);
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
}
