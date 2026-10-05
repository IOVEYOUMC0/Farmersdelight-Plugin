package com.huidu.farmersdelight.pack;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The auto-state budget: how many allocations CraftEngine is asked for versus how many each group can hand
 * out.
 *
 *
 * CraftEngine allocates one state per APPEARANCE, not per block: a plain {@code auto_state: x} is keyed
 * {@code "<block>[appearance=<name>]"} (AbstractBlockManager:642/646/650), and only the advanced form
 * {@code auto_state: {type: x, id: y}} reuses one allocation for every appearance that names the same id
 * (:640, VisualBlockStateAllocator.requestAutoState). The food family used to ask the tripwire group for
 * ~196 allocations while that group only owns 63, which the occupancy guard cannot see because auto_state is
 * not a pin. This test makes that arithmetic fail the build instead.
 */
class AutoStateBudgetTest {

    /**
     * Candidates per group. The numbers for the groups the pack uses are counted from the pack CraftEngine
     * ships, internal/configuration/mappings.yml: note_block 1300 (26 instruments x 25 notes x 2 powered,
     * including the 1.21.2+ trumpet ones), each mushroom block 63, tripwire 126 and its attached halves 63
     * each. The remaining groups are the block sets in AutoStateGroup (core/.../block/AutoStateGroup.java)
     * times their property combinations, and are conservative.
     */
    private static final Map<String, Integer> CAPACITY = Map.ofEntries(
            Map.entry("solid", 1489),
            Map.entry("note_block", 1300),
            Map.entry("mushroom", 189),
            Map.entry("mushroom_stem", 63),
            Map.entry("tripwire", 126),
            Map.entry("higher_tripwire", 63),
            Map.entry("lower_tripwire", 63),
            Map.entry("twisting_vines", 52),
            Map.entry("weeping_vines", 52),
            Map.entry("cave_vines", 52),
            Map.entry("kelp", 26),
            Map.entry("sugar_cane", 16),
            Map.entry("cactus", 16),
            Map.entry("sapling", 16),
            Map.entry("chorus", 64),
            Map.entry("pressure_plate", 32),
            Map.entry("leaves", 616),
            Map.entry("non_tintable_leaves", 154),
            Map.entry("tintable_leaves", 308),
            Map.entry("waterlogged_leaves", 616),
            Map.entry("waterlogged_tintable_leaves", 308));

    /** The mushroom half of solid: staying inside it keeps the blocks off note_block behaviour. */
    private static final int INERT_SOLID = 189;

    /** The ten owners of the food family plus the shared template they inherit from. */
    private static final List<String> FAMILY = List.of(
            "farmersdelight:sliceable_pie_states",
            "farmersdelight:apple_pie",
            "farmersdelight:sweet_berry_cheesecake",
            "farmersdelight:chocolate_pie",
            "farmersdelight:pumpkin_pie",
            "farmersdelight:gleaming_salad_block",
            "farmersdelight:honey_glazed_ham_block",
            "farmersdelight:rice_roll_medley_block",
            "farmersdelight:roast_chicken_block",
            "farmersdelight:shepherds_pie_block",
            "farmersdelight:stuffed_pumpkin_block");

    @Test
    void everyAutoStateGroupIsAskedForNoMoreStatesThanItHas() {
        Map<String, List<String>> requests = allocationKeys(false);
        assertTrue(!requests.isEmpty(), "the pack has to use auto_state somewhere");
        for (Map.Entry<String, List<String>> entry : requests.entrySet()) {
            Integer capacity = CAPACITY.get(entry.getKey());
            assertNotNull(capacity, "unknown auto-state group " + entry.getKey() + " in " + entry.getValue());
            assertTrue(entry.getValue().size() <= capacity,
                    "group " + entry.getKey() + " is asked for " + entry.getValue().size()
                            + " allocations but owns " + capacity + ": " + entry.getValue());
        }
    }

    @Test
    void theFoodFamilySharesOneSolidStatePerBlock() {
        List<String> solid = allocationKeys(false).getOrDefault("solid", List.of());
        assertEquals(11, solid.size(), "one shared allocation per owner plus the template: " + solid);
        for (String key : solid) {
            assertTrue(key.startsWith("solid[id=farmersdelight:"), "every key is the advanced shared form: " + key);
            String owner = key.substring("solid[id=".length(), key.length() - 1);
            assertTrue(FAMILY.contains(owner), "the id is the owner's own id: " + key);
        }
        assertTrue(solid.size() <= INERT_SOLID, "and it stays inside the inert mushroom states: " + solid);
    }

    /**
     * Proves the shared form is what keeps the family viable: with a plain {@code auto_state: solid} the ten
     * blocks alone ask for 196 allocations (132 own appearances + 4 x the template's 16) and the template entry
     * another 16, which overflows the inert mushroom half of the pool. Dropping the {@code id} therefore fails
     * this suite.
     */
    @Test
    void droppingTheSharedIdWouldAskForFarMoreStates() {
        List<String> plain = allocationKeys(true).getOrDefault("solid", List.of());
        assertTrue(plain.size() >= 196, "plain auto_state is keyed per appearance: " + plain.size());
        assertTrue(plain.size() > INERT_SOLID,
                "which overflows the inert half of the pool (" + INERT_SOLID + "): " + plain.size());
    }

    /**
     * Allocation keys, exactly as AbstractBlockManager builds them, with template inheritance expanded: a block
     * inherits the appearances of every template it lists, and {@code ${__ID__}} resolves to the owning block.
     * With {@code plainAsAppearances} the advanced form is treated as plain, which is how a dropped {@code id}
     * behaves.
     */
    private static Map<String, List<String>> allocationKeys(boolean plainAsAppearances) {
        Map<String, List<String>> requests = new LinkedHashMap<>();
        ConfigurationSection food = pack("food_block.yml");
        ConfigurationSection templates = food.getConfigurationSection("templates");
        collect(templates, templates, requests, plainAsAppearances);
        collect(food.getConfigurationSection("blocks"), templates, requests, plainAsAppearances);
        collect(pack("blocks.yml").getConfigurationSection("block"), null, requests, plainAsAppearances);
        return requests;
    }

    private static void collect(ConfigurationSection owners, ConfigurationSection templates,
                                Map<String, List<String>> requests, boolean plainAsAppearances) {
        if (owners == null) {
            return;
        }
        for (String owner : owners.getKeys(false)) {
            List<Map.Entry<String, ConfigurationSection>> appearances = new ArrayList<>();
            addAppearances(appearances, owners.getConfigurationSection(owner + ".states.appearances"));
            if (templates != null) {
                for (String template : owners.getStringList(owner + ".template")) {
                    addAppearances(appearances, templates.getConfigurationSection(template + ".states.appearances"));
                }
            }
            for (Map.Entry<String, ConfigurationSection> entry : appearances) {
                ConfigurationSection config = entry.getValue();
                Object auto = config.get("auto_state");
                if (auto == null) {
                    continue;
                }
                String group = auto instanceof String plain ? plain : config.getString("auto_state.type");
                String id = auto instanceof String ? null : config.getString("auto_state.id");
                if (id != null) {
                    id = id.replace("${__ID__}", owner).replace("${__NAMESPACE__}", "farmersdelight");
                }
                String key = id == null || plainAsAppearances
                        ? owner + "[appearance=" + entry.getKey() + "]"
                        : group + "[id=" + id + "]";
                List<String> list = requests.computeIfAbsent(group, ignored -> new ArrayList<>());
                if (!list.contains(key)) {
                    list.add(key);
                }
            }
        }
    }

    private static void addAppearances(List<Map.Entry<String, ConfigurationSection>> appearances,
                                       ConfigurationSection section) {
        if (section == null) {
            return;
        }
        for (String name : section.getKeys(false)) {
            ConfigurationSection config = section.getConfigurationSection(name);
            if (config != null) {
                appearances.add(new AbstractMap.SimpleEntry<>(name, config));
            }
        }
    }

    private static ConfigurationSection pack(String file) {
        String resource = "craftengine/farmersdelight/configuration/" + file;
        try (InputStream stream = AutoStateBudgetTest.class.getResourceAsStream("/" + resource)) {
            assertNotNull(stream, "missing pack resource: " + resource);
            YamlConfiguration configuration = new YamlConfiguration();
            configuration.loadFromString(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            return configuration;
        } catch (Exception error) {
            throw new AssertionError("cannot read " + resource, error);
        }
    }
}
