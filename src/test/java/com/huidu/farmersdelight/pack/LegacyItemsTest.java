package com.huidu.farmersdelight.pack;

import com.huidu.farmersdelight.api.migration.LegacyIdMigration;
import com.huidu.farmersdelight.migration.FarmersDelightLegacyIds;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The items the 1.4 update renamed, kept for migration only.
 *
 *
 * The source side of FarmersDelightLegacyIds is the list: farmersdelight:barbecue_stick became
 * farmersdelight:cooked_meat_skewer and farmersdelight:basket became farmersdelight:bamboo_basket. Both stay
 * defined in the file they always lived in, carry a lore line that resolves a language key in every shipped
 * locale, declare their own category (declared in categories.yml and not listed in the normal tabs), and sit in
 * no tag and no recipe. The shared basket block still drops the new bamboo basket, and the ids still resolve
 * through the migration facility.
 *
 *
 * Data only: the pack files are read from disk, no server is involved.
 */
class LegacyItemsTest {

    private static final Path RESOURCES = Path.of("src", "main", "resources");
    private static final Path CONFIG = RESOURCES.resolve("craftengine/farmersdelight/configuration");
    private static final Path LANGS = RESOURCES.resolve(
            "craftengine/farmersdelight/resourcepack/assets/farmersdelight/lang");
    private static final String KEY = "farmersdelight.tooltip.deprecated";
    private static final String CATEGORY_KEY = "farmersdelight.category.deprecated";
    private static final String DEPRECATED = "farmersdelight:deprecated";
    private static final String BASKET = "farmersdelight:basket";
    private static final String BAMBOO = "farmersdelight:bamboo_basket";
    private static final String STICK = "farmersdelight:barbecue_stick";
    private static final List<String> LEGACY = List.of(STICK, BASKET);
    private static final Map<String, String> HOME = Map.of(BASKET, "blocks.yml", STICK, "items.yml");

    @AfterEach
    void forgetTheRegisteredIds() {
        LegacyIdMigration.clear();
    }

    @Test
    void noLegacyGroupFileExists() {
        assertFalse(Files.exists(CONFIG.resolve("legacy_items.yml")),
                "the legacy items stay in the files they always lived in, there is no separate group file");
    }

    @Test
    void everyLegacyItemCarriesTheDeprecatedLoreInEveryLocale() throws Exception {
        for (String id : LEGACY) {
            ConfigurationSection item = item(id);
            String lore = String.valueOf(item.getMapList("data.lore"));
            assertTrue(lore.contains("<lang:" + KEY + ">"),
                    id + " has to carry the deprecated marker through the language key");
            assertTrue(item.getMapList("settings.tags").isEmpty(), id + " must not carry tags any more");
        }

        Map<String, String> values = new LinkedHashMap<>();
        List<Path> locales = locales();
        assertEquals(17, locales.size(), "every shipped locale is checked: " + locales);
        for (Path locale : locales) {
            ConfigurationSection lang = yaml(locale);
            for (String key : List.of(KEY, CATEGORY_KEY)) {
                String value = lang.getString(key);
                assertNotNull(value, locale.getFileName() + " is missing " + key);
                assertFalse(value.isBlank(), locale.getFileName() + " has an empty " + key);
            }
            values.put(locale.getFileName().toString(), lang.getString(KEY));
        }
        assertFalse(values.get("en_us.json").equals(values.get("zh_cn.json")),
                "the two shipped languages each carry their own text");
    }

    @Test
    void everyLegacyItemSitsInItsOwnCategory() throws Exception {
        ConfigurationSection categories = pack("categories.yml").getConfigurationSection("categories");
        assertNotNull(categories, "categories.yml keeps its categories");
        ConfigurationSection deprecated = categories.getConfigurationSection(DEPRECATED);
        assertNotNull(deprecated, "the renamed items get a category of their own");
        assertEquals("farmersdelight:barbecue_stick", deprecated.getString("icon"));
        assertTrue(deprecated.getBoolean("hidden"), "the deprecated entries stay out of the normal tabs");
        assertEquals(new TreeSet<>(LEGACY), new TreeSet<>(deprecated.getStringList("list")),
                "exactly the legacy ids are listed");
        for (String id : LEGACY) {
            assertEquals(DEPRECATED, item(id).getString("category"),
                    id + " declares the deprecated category on the item itself");
        }
        for (String name : categories.getKeys(false)) {
            if (DEPRECATED.equals(name)) {
                continue;
            }
            List<String> listed = categories.getStringList(name + ".list").stream().filter(LEGACY::contains).toList();
            assertEquals(List.of(), listed, name + " still lists a deprecated id: " + listed);
        }
    }

    @Test
    void noLegacyItemSitsInATagOrARecipe() throws Exception {
        String tags = Files.readString(RESOURCES.resolve("common-tags.yml"));
        for (String id : LEGACY) {
            assertFalse(mentions(tags, id), id + " must not be a tag member any more");
        }
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(CONFIG)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".yml")).sorted().forEach(files::add);
        }
        assertTrue(files.size() > 5, "the pack ships several configuration files: " + files);
        for (Path file : files) {
            String recipes = recipesSection(Files.readString(file));
            for (String id : LEGACY) {
                assertFalse(mentions(recipes, id), id + " must not appear in the recipes of " + file.getFileName());
            }
        }
    }

    @Test
    void theLegacyBlockDropsTheNewItem() throws Exception {
        ConfigurationSection block = pack("blocks.yml").getConfigurationSection("block." + BASKET);
        assertNotNull(block, "the legacy block stays for the baskets already placed in old worlds");
        List<Map<?, ?>> pools = block.getMapList("loot.pools");
        assertEquals(1, pools.size(), "one pool");
        List<?> entries = (List<?>) pools.getFirst().get("entries");
        assertEquals(BAMBOO, ((Map<?, ?>) entries.getFirst()).get("item"),
                "a broken legacy block hands over the new item, never itself");
    }

    @Test
    void everyLegacyIdIsDefinedOnceInTheWholePack() throws Exception {
        Map<String, List<String>> itemDefinitions = new LinkedHashMap<>();
        Map<String, List<String>> blockDefinitions = new LinkedHashMap<>();
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(CONFIG)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".yml")).sorted().forEach(files::add);
        }
        assertTrue(files.size() > 5, "the pack ships several configuration files: " + files);
        for (Path file : files) {
            // CraftEngine reads every configuration file and merges the sections, so a second definition of the
            // same id in another file is the cross-file duplicate the single-file key check cannot see.
            List<String> lines = List.of(Files.readString(file).split("\n"));
            String section = null;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (!line.isBlank() && !line.startsWith(" ") && line.endsWith(":")) {
                    section = line.substring(0, line.length() - 1);
                    continue;
                }
                if (line.startsWith("  ") && !line.startsWith("   ") && line.endsWith(":")) {
                    String id = line.trim();
                    id = id.substring(0, id.length() - 1);
                    Map<String, List<String>> target = "items".equals(section) ? itemDefinitions
                            : "block".equals(section) ? blockDefinitions : null;
                    if (target != null) {
                        target.computeIfAbsent(id, ignored -> new ArrayList<>())
                                .add(file.getFileName() + ":" + (i + 1));
                    }
                }
            }
        }
        for (String id : LEGACY) {
            assertEquals(List.of(HOME.get(id)), itemDefinitions.getOrDefault(id, List.of()).stream()
                            .map(entry -> entry.substring(0, entry.indexOf(':'))).distinct().toList(),
                    id + " has to have exactly one item definition: " + itemDefinitions.get(id));
        }
        assertEquals(1, blockDefinitions.getOrDefault(BASKET, List.of()).size(),
                "the shared block id is defined once: " + blockDefinitions.get(BASKET));
        assertTrue(blockDefinitions.containsKey(BAMBOO) && blockDefinitions.containsKey("farmersdelight:wooden_basket"),
                "the other two basket blocks are defined too: " + blockDefinitions.keySet());
        assertFalse(blockDefinitions.containsKey(STICK), "the legacy skewer is an item only, it has no block");
    }

    @Test
    void theLegacyIdsStillResolveForMigration() throws Exception {
        for (String id : LEGACY) {
            assertNotNull(item(id), id + " has to stay defined so stacks already in the world resolve");
        }
        assertNotNull(pack("blocks.yml").getConfigurationSection("items." + BAMBOO),
                "the replacement of " + BASKET + " stays defined");
        assertNotNull(pack("items.yml").getConfigurationSection("items.farmersdelight:cooked_meat_skewer"),
                "the replacement of " + STICK + " stays defined");

        FarmersDelightLegacyIds.register();
        assertEquals(BAMBOO, LegacyIdMigration.resolveId(BASKET),
                "the migration facility still maps the legacy basket to the bamboo basket");
        assertEquals("farmersdelight:cooked_meat_skewer", LegacyIdMigration.resolveId(STICK),
                "and the legacy skewer to the cooked meat skewer");
    }

    /** True when the text names that exact id: a longer id that starts with it does not count. */
    private static boolean mentions(String text, String id) {
        return Pattern.compile("(?<![\\w:])" + Pattern.quote(id) + "(?![\\w])").matcher(text).find();
    }

    /** The text under the top-level recipes key of one configuration file. */
    private static String recipesSection(String text) {
        List<String> lines = List.of(text.split("\n"));
        int start = lines.indexOf("recipes:");
        if (start < 0) {
            return "";
        }
        StringBuilder section = new StringBuilder();
        for (int i = start + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.isBlank() && !line.startsWith(" ") && !line.startsWith("#")) {
                break;
            }
            section.append(line).append('\n');
        }
        return section.toString();
    }

    /** The item definition of a legacy id, in the file it lives in. */
    private static ConfigurationSection item(String id) throws Exception {
        ConfigurationSection items = pack(HOME.get(id)).getConfigurationSection("items");
        assertNotNull(items, HOME.get(id) + " keeps its items section");
        ConfigurationSection item = items.getConfigurationSection(id);
        assertNotNull(item, id + " has to keep its item definition in " + HOME.get(id));
        return item;
    }

    private static List<Path> locales() throws Exception {
        List<Path> locales = new ArrayList<>();
        try (var stream = Files.list(LANGS)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().forEach(locales::add);
        }
        return locales;
    }

    private static ConfigurationSection pack(String file) throws Exception {
        Path path = CONFIG.resolve(file);
        assertTrue(Files.exists(path), path.toString());
        return yaml(path);
    }

    private static ConfigurationSection yaml(Path path) throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.loadFromString(Files.readString(path, StandardCharsets.UTF_8));
        return configuration;
    }
}
