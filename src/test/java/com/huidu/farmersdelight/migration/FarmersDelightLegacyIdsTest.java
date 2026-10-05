package com.huidu.farmersdelight.migration;

import com.huidu.farmersdelight.api.migration.LegacyIdMigration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the 1.4 skewer rename on the side the pack owns: the plugin registers
 * farmersdelight:barbecue_stick -> farmersdelight:cooked_meat_skewer, the legacy definition stays in the
 * pack as the migration carrier, no recipe produces the removed item any more, and the snacks tag is
 * back to upstream's list.
 *
 *
 * Everything here is data plus the mapping table, so it runs without a server: no ItemStack is constructed
 * (a bare stack cannot be built offline), and the pack files are read through the classpath.
 */
class FarmersDelightLegacyIdsTest {

    private static final String LEGACY = "farmersdelight:barbecue_stick";
    private static final String CURRENT = "farmersdelight:cooked_meat_skewer";

    @AfterEach
    void reset() {
        LegacyIdMigration.clear();
        LegacyIdMigration.setConflictReporter(null);
    }

    @Test
    void theRegistrationMapsTheRenamedIdsOntoTheirReplacements() {
        FarmersDelightLegacyIds.register();

        assertEquals(CURRENT, LegacyIdMigration.resolveId(LEGACY), "1.4 renamed the stick to the cooked skewer");
        assertEquals("farmersdelight:bamboo_basket", LegacyIdMigration.resolveId("farmersdelight:basket"),
                "1.3.4 renamed the basket to the bamboo basket");
        assertEquals(2, LegacyIdMigration.size());
        assertFalse(LegacyIdMigration.isEmpty(), "the automatic hooks only run while the table is not empty");
        assertEquals(0, LegacyIdMigration.conflictCount());
        assertNull(LegacyIdMigration.resolveId("farmersdelight:meat_skewer"),
                "the raw skewer is a new id, not a migration target");
        assertNull(LegacyIdMigration.resolveId("farmersdelight:wooden_basket"),
                "the wooden basket is a new id, not a migration target");
    }

    @Test
    void registeringTwiceKeepsOneMappingAndReportsNothing() {
        List<String> reports = new ArrayList<>();
        LegacyIdMigration.setConflictReporter(reports::add);

        FarmersDelightLegacyIds.register();
        FarmersDelightLegacyIds.register();

        assertEquals(2, LegacyIdMigration.size(), "the service may start twice; the table must not grow");
        assertEquals(0, LegacyIdMigration.conflictCount(), "an identical re-registration is not a conflict");
        assertEquals(List.of(), reports);
        assertEquals(CURRENT, LegacyIdMigration.resolveId(LEGACY));
    }

    @Test
    void theBasketDefinitionsStayAndTheTwoNewBasketsAreInPlace() {
        ConfigurationSection blocksFile = pack("craftengine/farmersdelight/configuration/blocks.yml");
        ConfigurationSection basketItems = blocksFile.getConfigurationSection("items");
        ConfigurationSection basketBlocks = blocksFile.getConfigurationSection("block");
        assertNotNull(basketItems, "blocks.yml has to keep its items section");
        assertNotNull(basketBlocks, "blocks.yml has to keep its block section");

        ConfigurationSection legacyItems = pack("craftengine/farmersdelight/configuration/blocks.yml")
                .getConfigurationSection("items");
        assertNotNull(legacyItems, "blocks.yml has to keep its items section");
        assertTrue(legacyItems.isConfigurationSection("farmersdelight:basket"),
                "the legacy basket item stays until old stacks are gone, in the file it always lived in");
        assertTrue(basketBlocks.isConfigurationSection("farmersdelight:basket"),
                "the legacy basket block stays: a placed basket keeps its old id, which the facility does not"
                        + " migrate, and removing the definition would turn it into air");
        for (String name : new String[]{"bamboo_basket", "wooden_basket"}) {
            assertTrue(basketItems.isConfigurationSection("farmersdelight:" + name),
                    name + " needs an item definition (the block_item behaviour places the block)");
            assertTrue(basketBlocks.isConfigurationSection("farmersdelight:" + name),
                    name + " keeps its own block definition");
        }
    }

    @Test
    void noRecipeProducesTheRemovedBasketAndBothNewOnesHaveOne() {
        List<String> producers = new ArrayList<>();
        List<String> newBaskets = new ArrayList<>();
        for (ConfigurationSection configuration : recipeFiles()) {
            ConfigurationSection recipes = configuration.getConfigurationSection("recipes");
            if (recipes == null) {
                continue;
            }
            for (String key : recipes.getKeys(false)) {
                ConfigurationSection result = recipes.getConfigurationSection(key + ".result");
                if (result == null) {
                    continue;
                }
                String id = result.getString("id");
                if ("farmersdelight:basket".equals(id)) {
                    producers.add(key);
                } else if ("farmersdelight:bamboo_basket".equals(id) || "farmersdelight:wooden_basket".equals(id)) {
                    newBaskets.add(id);
                }
            }
        }

        assertEquals(List.of(), producers, "the renamed basket is no longer crafted");
        assertTrue(newBaskets.contains("farmersdelight:bamboo_basket"), newBaskets.toString());
        assertTrue(newBaskets.contains("farmersdelight:wooden_basket"), newBaskets.toString());
        assertEquals(2, newBaskets.size(), "exactly one recipe per new basket: " + newBaskets);
    }

    @Test
    void theLegacyDefinitionAndTheTargetBothStayInThePack() {
        ConfigurationSection legacy = items();
        ConfigurationSection items = items();
        assertNotNull(legacy, "items.yml has to keep its items section");

        assertTrue(legacy.isConfigurationSection(LEGACY),
                "the legacy id must keep its definition, otherwise CraftEngine drops the old stacks before"
                        + " the migration can see them");
        assertTrue(items.isConfigurationSection(CURRENT), "the replacement has to exist in the same pack");
    }

    @Test
    void noRecipeProducesTheRemovedItemAnyMore() {
        List<String> producers = new ArrayList<>();
        List<String> retargeted = new ArrayList<>();
        for (ConfigurationSection configuration : recipeFiles()) {
            ConfigurationSection recipes = configuration.getConfigurationSection("recipes");
            if (recipes == null) {
                continue;
            }
            for (String key : recipes.getKeys(false)) {
                ConfigurationSection result = recipes.getConfigurationSection(key + ".result");
                if (result == null) {
                    continue;
                }
                String id = result.getString("id");
                if (LEGACY.equals(id)) {
                    producers.add(key);
                } else if (CURRENT.equals(id) && key.startsWith("farmersdelight:barbecue_stick_")) {
                    retargeted.add(key);
                }
            }
        }

        assertEquals(List.of(), producers, "1.4 no longer lets the barbecue stick be crafted");
        assertEquals(9, retargeted.size(),
                "the nine barbecue_stick_* variants have to craft the 1.4 target instead: " + retargeted);

        ConfigurationSection skewers = pack("craftengine/farmersdelight/configuration/recipes.yml");
        assertTrue(skewers.isConfigurationSection("recipes"), "the skewer recipes live under the recipes root key");
    }

    @Test
    void theSnacksTagMatchesUpstreamsSixteenItems() {
        ConfigurationSection items = items();

        List<String> snacks = new ArrayList<>();
        for (String key : items.getKeys(false)) {
            ConfigurationSection definition = items.getConfigurationSection(key);
            if (definition == null) {
                continue;
            }
            if (definition.getStringList("settings.tags").contains("farmersdelight:snacks")) {
                snacks.add(key);
            }
        }

        assertEquals(16, snacks.size(), "upstream 1.4 snacks has sixteen members: " + snacks);
        assertFalse(snacks.contains(LEGACY), "the removed item is no longer a snack: " + snacks);
        assertTrue(snacks.contains("farmersdelight:meat_skewer"), snacks.toString());
        assertTrue(snacks.contains("farmersdelight:cooked_vegetable_skewer"), snacks.toString());
    }

    /** The pack's items.yml, parsed from the classpath so the test needs no running server. */
    private static ConfigurationSection items() {
        return pack("craftengine/farmersdelight/configuration/items.yml").getConfigurationSection("items");
    }

    /**
     * Every configuration file that holds pack recipes. The recipes live in one merged file, so a check that
     * used to read one of the item files has to walk the files that actually carry the recipes root key.
     */
    private static List<ConfigurationSection> recipeFiles() {
        Path directory = Path.of("src", "main", "resources", "craftengine", "farmersdelight", "configuration");
        try (Stream<Path> files = Files.list(directory)) {
            List<ConfigurationSection> holders = new ArrayList<>();
            for (Path path : files.filter(file -> file.getFileName().toString().endsWith(".yml")).sorted().toList()) {
                ConfigurationSection configuration =
                        pack("craftengine/farmersdelight/configuration/" + path.getFileName());
                if (configuration.isConfigurationSection("recipes")) {
                    holders.add(configuration);
                }
            }
            assertFalse(holders.isEmpty(), "no configuration file carries the recipes root key");
            return holders;
        } catch (IOException error) {
            throw new AssertionError("cannot list " + directory, error);
        }
    }

    private static ConfigurationSection pack(String resource) {
        try (InputStream stream = FarmersDelightLegacyIdsTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream, "missing pack resource: " + resource);
            YamlConfiguration configuration = new YamlConfiguration();
            configuration.loadFromString(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            return configuration;
        } catch (Exception error) {
            throw new AssertionError("cannot read " + resource, error);
        }
    }
}
