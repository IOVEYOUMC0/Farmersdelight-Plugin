package com.huidu.farmersdelight.loot;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the single-site luck pilot: the wild onion drop floats, the other twelve sites in this repository keep
 * the exact shape they had.
 *
 * The shape is forced by what CraftEngine registers. Its formula registry holds exactly two entries,
 * craftengine:ore_drops and craftengine:binomial_with_bonus_count (Formulas.java:12-13 in the reference
 * source), the type is resolved through the CE namespace and an unknown one throws
 * loot.function.formula.unknown_type while the pack loads (Formulas.java:24-32). The vanilla
 * uniform_bonus_count that upstream uses for wild crops therefore cannot be written here, and the same
 * registry is what the 26.8.2, 26.9.1 and 26.10 jars carry. CropDrops adds one count per successful roll
 * over level + extra rolls (CropDrops.java:18-25), so extra stays 0 to keep "no Fortune, no bonus" for every
 * level, and the spread comes from the probability alone.
 */
class LuckFormulaPilotTest {

    private static final Path CONFIG = Path.of("src", "main", "resources", "craftengine", "farmersdelight",
            "configuration");
    private static final List<String> FILES = List.of("blocks.yml", "wild_plants.yml", "vanilla_crops.yml",
            "crops.yml");
    private static final Set<String> CRAFT_ENGINE_FORMULA_TYPES = Set.of("binomial_with_bonus_count", "ore_drops");
    private static final String PILOT_FILE = "wild_plants.yml";
    private static final String PILOT_ITEM = "farmersdelight:onion";

    @Test
    void thePilotSiteFloatsWithoutEverExceedingTheBaseDrop() {
        Site pilot = pilot();

        assertEquals("binomial_with_bonus_count", pilot.type());
        assertEquals(0, pilot.extra(),
                "extra is a constant number of extra rolls (CropDrops.java:19), so a non-zero value would add "
                        + "drops without Fortune and push low levels past the upstream range");
        assertEquals(0.5, pilot.probability(), 1.0e-9,
                "probability 1.0 makes the bonus deterministic again, which is the deviation this pilot removes");
    }

    @Test
    void theOtherSitesKeepTheirExactShape() {
        List<Site> sites = sites();
        Map<String, Integer> shapes = new HashMap<>();
        for (Site site : sites) {
            shapes.merge(site.extra() + "/" + site.probability(), 1, Integer::sum);
        }

        assertEquals(9, sites.size(), "this repository ships nine apply_bonus formulas; a new or removed "
                + "one has to be a deliberate change, not a side effect of the pilot");
        assertEquals(Map.of("0/1.0", 2, "0/0.5", 3, "3/0.5714286", 4), shapes,
                "only the wild onion may differ from the shape the other sites had before the pilot");
        assertEquals(1, sites.stream().filter(site -> site.file().equals(PILOT_FILE)
                        && site.item().equals(PILOT_ITEM)).count(),
                "the pilot is the single wild onion entry in wild_plants.yml");
    }

    @Test
    void everyFormulaTypeIsOneCraftEngineRegisters() {
        for (Site site : sites()) {
            assertTrue(CRAFT_ENGINE_FORMULA_TYPES.contains(site.type()),
                    site.file() + " uses formula type " + site.type() + ", which CraftEngine does not register; "
                            + "the pack would fail to load with loot.function.formula.unknown_type");
        }
        assertFalse(sites().isEmpty());
    }

    @Test
    void pilotDrawsStayInsideTheUpstreamRangeAndVary() {
        Site pilot = pilot();
        Random random = new Random(20_260_101L);
        Set<Integer> outcomesAtFortuneThree = new TreeSet<>();

        for (int level = 0; level <= 3; level++) {
            for (int draw = 0; draw < 400; draw++) {
                int count = applyBonus(1, level, pilot.extra(), pilot.probability(), random);
                if (level == 0) {
                    assertEquals(1, count, "without Fortune the wild crop keeps its single base drop");
                }
                assertTrue(count <= 1 + 2 * level,
                        "upstream adds 0..2*level through uniform_bonus_count, so " + count + " at level " + level
                                + " would sit above the upstream range");
                if (level == 3) {
                    outcomesAtFortuneThree.add(count);
                }
            }
        }
        assertTrue(outcomesAtFortuneThree.size() > 1,
                "Fortune III has to produce more than one outcome, which is what the pilot is for");
    }

    private static int applyBonus(int base, int level, int extra, double probability, Random random) {
        int count = base;
        for (int roll = 0; roll < level + extra; roll++) {
            if (random.nextFloat() < probability) {
                count++;
            }
        }
        return count;
    }

    private static Site pilot() {
        for (Site site : sites()) {
            if (site.file().equals(PILOT_FILE) && site.item().equals(PILOT_ITEM)) {
                return site;
            }
        }
        throw new AssertionError("the wild onion entry has to keep its apply_bonus formula");
    }

    private static List<Site> sites() {
        List<Site> sites = new ArrayList<>();
        for (String file : FILES) {
            Path path = CONFIG.resolve(file);
            assertTrue(Files.exists(path), path.toString());
            collect(file, YamlConfiguration.loadConfiguration(path.toFile()), sites);
        }
        return sites;
    }

    /**
     * Walks the deserialized file. A nested YAML mapping comes back as a ConfigurationSection, but the
     * elements of a YAML list - and the loot entries this test is after sit inside lists - stay plain maps, so
     * both shapes have to be walked; sections alone would never reach an entry.
     */
    private static void collect(String file, Object node, List<Site> out) {
        if (node instanceof ConfigurationSection section) {
            addSite(file, section.get("item"), section.get("functions"), out);
            for (String key : section.getKeys(false)) {
                collect(file, section.get(key), out);
            }
        } else if (node instanceof Map<?, ?> map) {
            addSite(file, map.get("item"), map.get("functions"), out);
            for (Object value : map.values()) {
                collect(file, value, out);
            }
        } else if (node instanceof List<?> list) {
            for (Object value : list) {
                collect(file, value, out);
            }
        }
    }

    private static void addSite(String file, Object item, Object functions, List<Site> out) {
        if (!(item instanceof String id) || !(functions instanceof List<?> list)) {
            return;
        }
        for (Object function : list) {
            Object formula = value(function, "formula");
            if (formula == null) {
                continue;
            }
            Object extra = value(formula, "extra");
            Object probability = value(formula, "probability");
            out.add(new Site(file, id, String.valueOf(value(formula, "type")),
                    extra instanceof Number extraValue ? extraValue.intValue() : 1,
                    probability instanceof Number probabilityValue ? probabilityValue.doubleValue() : 0.5));
        }
    }

    private static Object value(Object node, String key) {
        if (node instanceof ConfigurationSection section) {
            return section.get(key);
        }
        if (node instanceof Map<?, ?> map) {
            return map.get(key);
        }
        return null;
    }

    private record Site(String file, String item, String type, int extra, double probability) {
    }
}
