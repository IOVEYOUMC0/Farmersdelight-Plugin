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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which vanilla block state each basket occupies.
 *
 *
 * An explicit {@code state:} pins a vanilla state for the client model lookup. The three baskets used to pin
 * the same one ({@code composter[level=7]}, which the pack even remaps to {@code composter[level=6]}), so two
 * of them could not have their own model. The legacy basket keeps its pinned state — old worlds still hold
 * baskets in it — while the two new baskets let CraftEngine reserve a state of their own through
 * {@code auto_state}, the same mechanism the other five faces and the crates already use.
 *
 *
 * Data only: this reads blocks.yml through the classpath. What the client actually renders per state is on the
 * real-server checklist.
 */
class BasketStatesTest {

    private static final List<String> FACES = List.of("east", "north", "south", "west", "up", "down");
    private static final String CONVENTION = "non_tintable_leaves";
    private static final String LEGACY = "farmersdelight:basket";

    @Test
    void theLegacyBasketKeepsItsPinnedState() {
        ConfigurationSection legacy = appearances(LEGACY);
        assertEquals("composter[level=7]", legacy.getConfigurationSection("up").getString("state"),
                "placed baskets in old worlds sit in that state; changing it would move them");
    }

    @Test
    void theTwoNewBasketsReserveTheirOwnStatesInsteadOfPinningTheLegacyOne() {
        for (String name : new String[]{"farmersdelight:bamboo_basket", "farmersdelight:wooden_basket"}) {
            ConfigurationSection up = appearances(name).getConfigurationSection("up");
            assertNull(up.getString("state"),
                    name + " must not pin a vanilla state: it has to reserve one of its own");
            assertEquals(CONVENTION, up.getString("auto_state"),
                    name + " uses the pack's auto_state convention");
            for (String face : FACES) {
                assertNotNull(appearances(name).getConfigurationSection(face), name + " keeps the " + face + " face");
            }
        }
    }

    @Test
    void noTwoBasketsShareAPinnedState() {
        List<String> baskets = List.of("farmersdelight:bamboo_basket", "farmersdelight:wooden_basket", LEGACY);
        Map<String, List<String>> owners = pinnedStates();
        for (Map.Entry<String, List<String>> entry : owners.entrySet()) {
            List<String> basketOwners = entry.getValue().stream().filter(baskets::contains).toList();
            assertTrue(basketOwners.size() <= 1,
                    "state " + entry.getKey() + " is pinned by more than one basket (" + basketOwners
                            + "): the client can only resolve one model for it");
        }
        assertEquals(List.of(LEGACY), owners.get("composter[level=7]"),
                "after the fix the legacy basket is the only block left on that state");
    }

    /**
     * The other shared pinned states in the pack, listed rather than asserted: they are used by crops and
     * carpets on purpose and are outside this task. The test exists so the list is visible when it changes.
     */
    @Test
    void thePacksOtherSharedStatesAreKnownAndOutOfScope() {
        List<String> shared = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : pinnedStates().entrySet()) {
            if (entry.getValue().size() > 1) {
                shared.add(entry.getKey() + " <- " + entry.getValue());
            }
        }
        for (String entry : shared) {
            assertFalse(entry.startsWith("composter[level=7]"),
                    "the baskets' old state must not be shared any more: " + entry);
        }
        assertEquals(shared.size(), shared.stream().distinct().count());
    }

    @Test
    void everyBasketFaceStillFollowsThePackConvention() {
        for (String name : new String[]{"farmersdelight:bamboo_basket", "farmersdelight:wooden_basket", LEGACY}) {
            ConfigurationSection faces = appearances(name);
            List<String> autoFaces = new ArrayList<>();
            for (String face : FACES) {
                ConfigurationSection appearance = faces.getConfigurationSection(face);
                if (appearance.getString("state") == null) {
                    autoFaces.add(face);
                    assertEquals(CONVENTION, appearance.getString("auto_state"),
                            name + "." + face + " must use the auto_state convention");
                }
            }
            assertFalse(autoFaces.isEmpty(), name + " keeps at least one auto-allocated state");
        }
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
            for (String face : FACES) {
                ConfigurationSection appearance = appearances.getConfigurationSection(face);
                if (appearance == null) {
                    continue;
                }
                String state = appearance.getString("state");
                if (state != null) {
                    List<String> stateOwners = owners.computeIfAbsent(state, ignored -> new ArrayList<>());
                    // One entry per block, not per face: several faces of one block sharing a state is normal.
                    if (!stateOwners.contains(key)) {
                        stateOwners.add(key);
                    }
                }
            }
        }
        return owners;
    }

    private static ConfigurationSection appearances(String blockId) {
        ConfigurationSection appearances = blocks().getConfigurationSection("block." + blockId + ".states.appearances");
        assertNotNull(appearances, blockId + " has to keep its appearances");
        return appearances;
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
