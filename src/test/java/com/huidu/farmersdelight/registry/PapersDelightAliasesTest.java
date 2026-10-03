package com.huidu.farmersdelight.registry;

import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The PapersDelight packs spell a few behaviour arguments differently from this plugin. Those translations are
 * what makes the aliases more than a rename, so they are checked here against the sections the packs actually
 * ship rather than only against the code that consumes them.
 */
class PapersDelightAliasesTest {

    @Test
    void stoveSoundBecomesTheCrackleSoundThisPluginReads() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sound", "farmersdelight:block.stove.crackle");
        values.put("interval", List.of(60, 100));
        values.put("volume", 1.0);
        values.put("pitch", List.of(0.9, 1.1));

        ConfigSection normalized = PapersDelightAliases.stoveSection(ConfigSection.ofRoot(values));

        assertEquals("farmersdelight:block.stove.crackle", normalized.getString("crackle-sound"));
        // The pack's own sound-loop keys have no counterpart and must not be mistaken for ours.
        assertFalse(normalized.containsKey("crackle-volume"));
    }

    @Test
    void anExplicitCrackleSoundWins() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sound", "pack:sound");
        values.put("crackle-sound", "minecraft:block.campfire.crackle");

        ConfigSection normalized = PapersDelightAliases.stoveSection(ConfigSection.ofRoot(values));

        assertEquals("minecraft:block.campfire.crackle", normalized.getString("crackle-sound"));
    }

    @Test
    void stoveBurnStaysAtThisPluginsDefaults() {
        // The pack deals damage through a separate behaviour this plugin does not implement, so switching our
        // own burn off would leave the station harmless.
        ConfigSection normalized = PapersDelightAliases.stoveSection(ConfigSection.ofRoot(new LinkedHashMap<>()));

        assertFalse(normalized.containsKey("burn.enabled"));
        assertFalse(normalized.containsKey("ignite.enabled"));
        assertFalse(normalized.containsKey("extinguish.enabled"));
    }

    @Test
    void skilletItemGetsTheCookingModelItsPackShips() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("block", "farmersdelight:skillet");

        ConfigSection normalized = PapersDelightAliases.skilletItemSection(ConfigSection.ofRoot(values));

        assertEquals("farmersdelight:skillet_cooking", normalized.getString("cooking-model"));
        assertEquals("farmersdelight:skillet", normalized.getString("block"));
    }

    @Test
    void aConfiguredCookingModelIsLeftAlone() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("cooking-model", "pack:custom_skillet");

        ConfigSection normalized = PapersDelightAliases.skilletItemSection(ConfigSection.ofRoot(values));

        assertEquals("pack:custom_skillet", normalized.getString("cooking-model"));
    }

    @Test
    void aMissingSectionStillProducesAUsableOne() {
        // The behaviour factory may be handed a null section by an unusual configuration; the aliases must not
        // turn that into a crash while building the replacement section.
        assertTrue(PapersDelightAliases.stoveSection(null).keySet().isEmpty());
        assertEquals("farmersdelight:skillet_cooking",
                PapersDelightAliases.skilletItemSection(null).getString("cooking-model"));
    }

    @Test
    void unsupportedIdentifiersAreAllNamespacedAndUnique() {
        var unsupported = PapersDelightAliases.unsupported();

        assertEquals(unsupported.size(), unsupported.stream().distinct().count(), "duplicate entry");
        for (String id : unsupported) {
            assertTrue(id.startsWith("papersdelight:"), id + " is not a PapersDelight identifier");
        }
    }
}
