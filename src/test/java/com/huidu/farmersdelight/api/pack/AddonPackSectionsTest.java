package com.huidu.farmersdelight.api.pack;

import com.huidu.farmersdelight.pack.PackSection;
import net.momirealms.craftengine.core.pack.CachedConfigSection;
import net.momirealms.craftengine.core.pack.Pack;
import net.momirealms.craftengine.core.pack.PackMeta;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import org.bukkit.configuration.ConfigurationSection;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the bridge from a CraftEngine pack section to the configuration shape the readers expect, plus the
 * section bookkeeping (per-id routing, namespace suffix, published snapshots). Uses FarmersDelight's own
 * claim so the addon-facing api is exercised with real section ids.
 */
class AddonPackSectionsTest {

    private static final Map<String, String> ROOTS = roots();

    private static Map<String, String> roots() {
        Map<String, String> roots = new LinkedHashMap<>();
        for (PackSection section : PackSection.values()) {
            roots.put(section.sectionId(), section.rootKey());
        }
        return roots;
    }

    private static Pack pack(String folder, String namespace) {
        return new Pack(Path.of("resources", folder),
                new PackMeta("author", "description", "1.0", namespace), true, new String[0]);
    }

    private static void feed(AddonPackSections parser, Pack pack, String sectionKey, Map<String, Object> values) {
        Path file = pack.folder().resolve("configuration/farmersdelight/sections.yml");
        parser.addConfig(new CachedConfigSection(pack, file, ConfigSection.of(sectionKey, values), null));
    }

    @Test
    void publishesACookingPotSectionUnderTheRootKeyTheReaderLooksUp() {
        AddonPackSections parser = AddonPackSections.createForTesting(ROOTS);
        Pack pack = pack("corndelight", "corndelight");
        feed(parser, pack, "cooking_recipes", Map.of("boiled_corn", Map.of(
                "ingredients", List.of("#c:crops/corn"),
                "result", "corndelight:boiled_corn",
                "experience", 0.15)));

        parser.loadAll();

        List<AddonPackSections.Section> sections = parser.sections("cooking_recipes");
        assertEquals(1, sections.size());
        AddonPackSections.Section section = sections.get(0);
        assertEquals("corndelight/configuration/farmersdelight/sections.yml", section.source());
        assertEquals("corndelight", section.namespace());

        ConfigurationSection recipes = section.config().getConfigurationSection("cooking_pot_recipes");
        assertNotNull(recipes);
        assertEquals(List.of("boiled_corn"), List.copyOf(recipes.getKeys(false)));
        ConfigurationSection recipe = recipes.getConfigurationSection("boiled_corn");
        assertNotNull(recipe);
        assertEquals("corndelight:boiled_corn", recipe.getString("result"));
        assertEquals(List.of("#c:crops/corn"), recipe.getStringList("ingredients"));
        assertEquals(0.15, recipe.getDouble("experience"), 1.0e-9);
    }

    @Test
    void keepsListsOfMappingsReadableAsMapLists() {
        AddonPackSections parser = AddonPackSections.createForTesting(ROOTS);
        Map<String, Object> slot = new LinkedHashMap<>();
        slot.put("item", "minecraft:water_bucket");
        slot.put("name", "gui.special_recipe.sunlight.slot");
        feed(parser, pack("crabbersdelight", "crabbersdelight"), "special_recipes", Map.of("sunlight", Map.of(
                "display-type", "recipe",
                "inputs", List.of(slot))));

        parser.loadAll();

        ConfigurationSection cards = parser.sections("special_recipes").get(0).config()
                .getConfigurationSection("special_recipes");
        assertNotNull(cards);
        List<Map<?, ?>> inputs = cards.getConfigurationSection("sunlight").getMapList("inputs");
        assertEquals(1, inputs.size());
        assertEquals("minecraft:water_bucket", inputs.get(0).get("item"));
    }

    @Test
    void advancementNamespaceComesFromTheSectionSuffix() {
        AddonPackSections parser = AddonPackSections.createForTesting(ROOTS);
        Pack pack = pack("corndelight", "corndelight");
        feed(parser, pack, "farmersdelight_advancements", Map.of("root", Map.of("icon", "corndelight:corn")));
        feed(parser, pack, "farmersdelight_advancements#festival", Map.of("root", Map.of("icon", "festival:baozi")));

        parser.loadAll();

        List<AddonPackSections.Section> sections = parser.sections("farmersdelight_advancements");
        assertEquals(2, sections.size());
        assertEquals("corndelight", sections.get(0).namespace());
        assertEquals("festival", sections.get(1).namespace());
        for (AddonPackSections.Section section : sections) {
            assertNotNull(section.config().getConfigurationSection("advancements"));
        }
    }

    @Test
    void routingIgnoresUnknownRootKeysAndKeepsSectionsApart() {
        AddonPackSections parser = AddonPackSections.createForTesting(ROOTS);
        feed(parser, pack("corndelight", "corndelight"), "items", Map.of("corn", Map.of()));
        feed(parser, pack("corndelight", "corndelight"), "cutting_recipes", Map.of("corn", Map.of()));

        parser.loadAll();

        assertEquals(1, parser.sections("cutting_recipes").size());
        assertTrue(parser.sections("cooking_recipes").isEmpty());
        assertTrue(parser.sections("farmersdelight_advancements").isEmpty());
        assertTrue(parser.sections("special_recipes").isEmpty());
    }

    @Test
    void clearingTheRawStorageKeepsThePublishedSnapshots() {
        AddonPackSections parser = AddonPackSections.createForTesting(ROOTS);
        feed(parser, pack("corndelight", "corndelight"), "cooking_recipes", Map.of("corn", Map.of()));
        parser.loadAll();

        // CraftEngine clears the raw storage at the end of every load pass; readers run after that.
        parser.clearConfigs();

        assertEquals(1, parser.sections("cooking_recipes").size());
    }

    @Test
    void aRebuildReplacesSectionsThePackNoLongerDeclares() {
        AddonPackSections parser = AddonPackSections.createForTesting(ROOTS);
        feed(parser, pack("corndelight", "corndelight"), "cooking_recipes", Map.of("corn", Map.of()));
        parser.loadAll();
        assertEquals(1, parser.sections("cooking_recipes").size());

        parser.clearConfigs();
        parser.loadAll();

        assertTrue(parser.sections("cooking_recipes").isEmpty());
    }
}
