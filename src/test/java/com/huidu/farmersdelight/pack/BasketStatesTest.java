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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which vanilla block states the basket family occupies.
 *
 *
 * The basket and the bamboo basket are the same block with two ids, so they reference the same six states: five
 * per-face auto_state allocations that both blocks name by id, plus the historic composter[level=7] pin on the
 * up face that worlds saved before this change still hold their placed baskets in. Naming the same id in both
 * blocks is what makes CraftEngine hand out one allocation instead of one per block and face
 * (AbstractBlockManager:636-651). The wooden basket is not part of the share: it keeps its own per-appearance
 * states.
 *
 *
 * Data only: this reads blocks.yml through the classpath. What the client renders per state is on the
 * real-server checklist.
 */
class BasketStatesTest {

    private static final List<String> FACES = List.of("east", "north", "south", "west", "up", "down");
    private static final String BASKET = "farmersdelight:basket";
    private static final String BAMBOO = "farmersdelight:bamboo_basket";
    private static final String WOODEN = "farmersdelight:wooden_basket";
    private static final String LEGACY_PIN = "composter[level=7]";

    @Test
    void theThreeBasketBlocksStay() {
        for (String id : List.of(BASKET, BAMBOO, WOODEN)) {
            assertNotNull(blocks().getConfigurationSection("block." + id),
                    id + " keeps its own block definition");
        }
    }

    @Test
    void theBasketAndTheBambooBasketReferenceTheSameStates() {
        Map<String, String> basket = references(BASKET);
        Map<String, String> bamboo = references(BAMBOO);
        assertEquals(basket, bamboo,
                "the two ids have to occupy the same states, otherwise the second one costs extra states");
        assertEquals(FACES.size(), basket.size(), "every face is accounted for");
        for (String face : FACES) {
            if ("up".equals(face)) {
                assertEquals("state:" + LEGACY_PIN, basket.get(face),
                        "the up face keeps the historic pin both blocks share");
            } else {
                assertEquals("auto-id:non_tintable_leaves:basket_" + face, basket.get(face),
                        face + " names a shared allocation instead of reserving one per block");
            }
        }
    }

    @Test
    void theWoodenBasketKeepsItsOwnStates() {
        Map<String, String> wooden = references(WOODEN);
        assertFalse(wooden.containsValue("state:" + LEGACY_PIN),
                "the wooden basket must not be pulled into the shared pin");
        for (String face : FACES) {
            assertEquals("auto:non_tintable_leaves", wooden.get(face),
                    face + " keeps its own per-appearance allocation");
        }
        assertFalse(wooden.equals(references(BASKET)),
                "the wooden state set has to differ from the shared one");
    }

    @Test
    void onlyTheTwoBasketBlocksShareThePinnedState() {
        assertEquals(List.of(BAMBOO, BASKET), pinnedStates().get(LEGACY_PIN),
                "the deliberate share is exactly these two blocks");
        List<String> shared = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : pinnedStates().entrySet()) {
            if (entry.getValue().size() > 1) {
                shared.add(entry.getKey() + " <- " + entry.getValue());
            }
        }
        assertEquals(List.of(LEGACY_PIN + " <- [" + BAMBOO + ", " + BASKET + "]"), shared,
                "no other state in the pack is pinned by two owners: " + shared);
    }

    /** face -> a readable key for the state that face references. */
    private static Map<String, String> references(String blockId) {
        ConfigurationSection appearances = blocks()
                .getConfigurationSection("block." + blockId + ".states.appearances");
        assertNotNull(appearances, blockId + " has to keep its appearances");
        Map<String, String> references = new LinkedHashMap<>();
        for (String face : appearances.getKeys(false)) {
            ConfigurationSection appearance = appearances.getConfigurationSection(face);
            String pinned = appearance.getString("state");
            if (pinned != null) {
                references.put(face, "state:" + pinned);
                continue;
            }
            if (appearance.isConfigurationSection("auto_state")) {
                references.put(face, "auto-id:" + appearance.getString("auto_state.type") + ":"
                        + appearance.getString("auto_state.id"));
                continue;
            }
            String plain = appearance.getString("auto_state");
            references.put(face, "auto:" + plain);
        }
        return references;
    }

    /** Every pinned vanilla state in the pack, with the blocks that pin it. */
    private static Map<String, List<String>> pinnedStates() {
        Map<String, List<String>> owners = new LinkedHashMap<>();
        ConfigurationSection blocks = blocks().getConfigurationSection("block");
        for (String key : blocks.getKeys(false)) {
            ConfigurationSection appearances = blocks.getConfigurationSection(key + ".states.appearances");
            if (appearances == null) {
                continue;
            }
            for (String face : appearances.getKeys(false)) {
                String state = appearances.getConfigurationSection(face).getString("state");
                if (state != null) {
                    List<String> stateOwners = owners.computeIfAbsent(state, ignored -> new ArrayList<>());
                    // One entry per block, not per face: several faces of one block sharing a state is normal.
                    if (!stateOwners.contains(key)) {
                        stateOwners.add(key);
                    }
                }
            }
        }
        assertTrue(!owners.isEmpty(), "the pack still pins some states explicitly");
        return owners;
    }

    private static ConfigurationSection blocks() {
        String resource = "craftengine/farmersdelight/configuration/blocks.yml";
        try (InputStream stream = BasketStatesTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream, "missing pack resource: " + resource);
            YamlConfiguration configuration = new YamlConfiguration();
            configuration.loadFromString(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            return configuration;
        } catch (Exception error) {
            throw new AssertionError("cannot read " + resource, error);
        }
    }
}
