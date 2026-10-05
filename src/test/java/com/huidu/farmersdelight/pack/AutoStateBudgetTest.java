package com.huidu.farmersdelight.pack;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
 * {@code "<block>[appearance=<name>]"}, and only the advanced form {@code auto_state: {type: x, id: y}} reuses
 * one allocation for every appearance that names the same id (AbstractBlockManager:629-651,
 * VisualBlockStateAllocator.requestAutoState). The food family used to ask the tripwire group for 148-196
 * allocations while that group only owns 64 states, which the occupancy guard cannot see because auto_state is
 * not a pin. This test makes that arithmetic fail the build instead.
 */
class AutoStateBudgetTest {

    /** Candidates per group, from AutoStateGroup (core/.../block/AutoStateGroup.java). */
    private static final Map<String, Integer> CAPACITY = Map.ofEntries(
            Map.entry("solid", 992),
            Map.entry("note_block", 800),
            Map.entry("mushroom", 192),
            Map.entry("mushroom_stem", 64),
            Map.entry("tripwire", 128),
            Map.entry("higher_tripwire", 64),
            Map.entry("lower_tripwire", 64),
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
    private static final int INERT_SOLID = 192;

    @Test
    void everyAutoStateGroupIsAskedForNoMoreStatesThanItHas() {
        Map<String, List<String>> requests = allocationKeys();
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
        Map<String, List<String>> requests = allocationKeys();
        List<String> solid = requests.getOrDefault("solid", List.of());
        assertTrue(solid.size() <= INERT_SOLID,
                "the family has to stay inside the inert mushroom states: " + solid);
        assertTrue(!solid.contains("farmersdelight:rice_roll_medley_block"),
                "the block id must itself never become an allocation key");
    }

    /** Allocation key per appearance, exactly as AbstractBlockManager builds it. */
    private static Map<String, List<String>> allocationKeys() {
        Map<String, List<String>> requests = new LinkedHashMap<>();
        ConfigurationSection configuration = pack("food_block.yml");
        collect(configuration.getConfigurationSection("templates"), requests);
        collect(configuration.getConfigurationSection("blocks"), requests);
        ConfigurationSection blocks = pack("blocks.yml").getConfigurationSection("block");
        collect(blocks, requests);
        return requests;
    }

    private static void collect(ConfigurationSection owners, Map<String, List<String>> requests) {
        if (owners == null) {
            return;
        }
        for (String owner : owners.getKeys(false)) {
            ConfigurationSection appearances = owners.getConfigurationSection(owner + ".states.appearances");
            if (appearances == null) {
                continue;
            }
            for (String appearance : appearances.getKeys(false)) {
                ConfigurationSection config = appearances.getConfigurationSection(appearance);
                Object auto = config.get("auto_state");
                if (auto == null) {
                    continue;
                }
                String key;
                String group;
                if (auto instanceof String plain) {
                    group = plain;
                    key = owner + "[appearance=" + appearance + "]";
                } else {
                    group = config.getString("auto_state.type");
                    String id = config.getString("auto_state.id");
                    key = group + "[id=" + (id == null ? "" : id) + "]";
                }
                List<String> list = requests.computeIfAbsent(group, ignored -> new ArrayList<>());
                if (!list.contains(key)) {
                    list.add(key);
                }
            }
        }
    }

    private static ConfigurationSection pack(String file) {
        String resource = "craftengine/farmersdelight/configuration/" + file;
        try (InputStream stream = AutoStateBudgetTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream, "missing pack resource: " + resource);
            YamlConfiguration configuration = new YamlConfiguration();
            configuration.loadFromString(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            return configuration;
        } catch (Exception error) {
            throw new AssertionError("cannot read " + resource, error);
        }
    }
}
