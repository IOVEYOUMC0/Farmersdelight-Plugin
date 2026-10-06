package com.huidu.farmersdelight.visual;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The thresholds a culling pass runs under: what a type without an entry gets, what its own entry changes,
 * how far the window has to reach, and the bounds that keep a bad value from widening the pass.
 */
class DisplayViewSettingsTest {

    private static final String FENCE = "minecraft:mangrove_fence";
    private static final String GATE = "minecraft:mangrove_fence_gate";

    @Test
    void aTypeWithoutAnEntryUsesTheFallbackDistanceAndStaysCullable() {
        DisplayViewSettings settings = new DisplayViewSettings(64.0D, 8.0D, 16, Map.of());

        assertEquals(64.0D, settings.viewDistanceFor(FENCE), 0.0D);
        assertTrue(settings.cullingEnabled(FENCE));
    }

    @Test
    void aTypeEntryOverridesOnlyThatType() {
        DisplayViewSettings settings = new DisplayViewSettings(64.0D, 8.0D, 16,
                Map.of(GATE, new DisplayViewSettings.Type(true, 32.0D)));

        assertEquals(32.0D, settings.viewDistanceFor(GATE), 0.0D);
        assertEquals(64.0D, settings.viewDistanceFor(FENCE), 0.0D, "a type without an entry keeps the fallback");
    }

    @Test
    void cullingIsOnUnlessATypeTurnsItOff() {
        DisplayViewSettings settings = new DisplayViewSettings(64.0D, 8.0D, 16,
                Map.of(GATE, new DisplayViewSettings.Type(false, 32.0D)));

        assertTrue(settings.cullingEnabled(FENCE));
        assertFalse(settings.cullingEnabled(GATE));
    }

    @Test
    void theWindowReachesTheWidestCullableDistancePlusTheMargin() {
        DisplayViewSettings settings = new DisplayViewSettings(64.0D, 8.0D, 16, Map.of(
                FENCE, new DisplayViewSettings.Type(true, 128.0D),
                GATE, new DisplayViewSettings.Type(false, 512.0D)));

        assertEquals(136.0D, settings.windowRadius(), 0.0D,
                "a type that may not be culled does not widen the window");
    }

    @Test
    void aDistanceIsClampedToTheWidestUsefulRange() {
        DisplayViewSettings settings = new DisplayViewSettings(4096.0D, 8.0D, 16,
                Map.of(FENCE, new DisplayViewSettings.Type(true, 4096.0D)));

        assertEquals(DisplayViewSettings.MAX_VIEW_DISTANCE, settings.fallbackViewDistance(), 0.0D);
        assertEquals(DisplayViewSettings.MAX_VIEW_DISTANCE, settings.viewDistanceFor(FENCE), 0.0D);
        assertEquals(DisplayViewSettings.MAX_VIEW_DISTANCE + 8.0D, settings.windowRadius(), 0.0D);
    }

    @Test
    void aBudgetBelowOneStillAllowsOneCheck() {
        assertEquals(1, new DisplayViewSettings(64.0D, 8.0D, 0, Map.of()).checksPerPlayer());
        assertEquals(1, new DisplayViewSettings(64.0D, 8.0D, -5, Map.of()).checksPerPlayer());
        assertEquals(32, new DisplayViewSettings(64.0D, 8.0D, 32, Map.of()).checksPerPlayer());
    }

    /** The shipped file has to carry both keys the culler reads, and the per-type entries their two fields. */
    @Test
    void theShippedConfigCarriesTheCullingKeys() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(Files.readString(Path.of("src", "main", "resources", "config.yml")));

        assertTrue(config.isInt("performance.budgets.display-cull-checks-per-player"),
                "the per-player budget ships as an integer");

        ConfigurationSection types = config.getConfigurationSection("performance.proxy-display.display-culling");
        assertNotNull(types, "the per-type culling table ships");
        for (String key : types.getKeys(false)) {
            ConfigurationSection entry = types.getConfigurationSection(key);
            assertNotNull(entry, key + " is a section");
            assertTrue(entry.isBoolean("entity-culling"), key + " states whether it may be culled");
            assertTrue(entry.isDouble("view-distance"), key + " states the distance it is culled at");
        }
        assertTrue(types.getKeys(false).contains(FENCE), "the carrier the restorer restores is listed");
        assertTrue(types.getKeys(false).contains(GATE), "and so is its gate");
    }
}
