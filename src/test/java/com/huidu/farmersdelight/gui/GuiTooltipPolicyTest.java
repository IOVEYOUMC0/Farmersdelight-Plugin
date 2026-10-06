package com.huidu.farmersdelight.gui;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cooking-pot GUI mixes buttons with read-only indicators, and the indicators keep their tooltip:
 * hovering the bar is how a player reads the exact "Cooking Progress: N%" value, and the heat icon
 * says "Heated" or "Not Heated". Buttons keep their tooltip too, because the label and lore are what
 * tell the player what the click does; only the blank-name decoration filler hides it.
 */
class GuiTooltipPolicyTest {

    private static final Path GUI = Path.of("src/main/resources/gui.yml");

    @Test
    void everyReadOnlyIndicatorKeepsItsTooltip() throws IOException, InvalidConfigurationException {
        ConfigurationSection pot = cookingPot();
        for (String key : List.of("heat-active", "heat-inactive", "progress")) {
            ConfigurationSection item = pot.getConfigurationSection("items." + key);
            assertNotNull(item, "missing cooking-pot item " + key);
            assertFalse(item.getBoolean("hide-tooltip"),
                    key + " is a read-only indicator and keeps its tooltip");
        }

        List<Map<?, ?>> progressItems = pot.getMapList("progress-items");
        assertEquals(21, progressItems.size(), "the bar keeps its 5%-step frames");
        for (int index = 0; index < progressItems.size(); index++) {
            assertFalse(Boolean.TRUE.equals(progressItems.get(index).get("hide-tooltip")),
                    "progress-items[" + index + "] must keep the progress text visible on hover");
        }
    }

    @Test
    void theBlankDecorationFillerStillHidesItsTooltip() throws IOException, InvalidConfigurationException {
        ConfigurationSection background = cookingPot().getConfigurationSection("items.background");
        assertNotNull(background, "missing cooking-pot item background");
        assertTrue(background.getBoolean("hide-tooltip"), "the blank filler has no text worth showing");
    }

    @Test
    void buttonsKeepTheirTooltip() throws IOException, InvalidConfigurationException {
        ConfigurationSection item = cookingPot().getConfigurationSection("items.recipe");
        assertNotNull(item, "missing cooking-pot item recipe");
        assertFalse(item.getBoolean("hide-tooltip"), "recipe is a button and keeps its tooltip");
        assertNotNull(item.getString("name"), "recipe is a button and needs a label");
    }

    private static ConfigurationSection cookingPot() throws IOException, InvalidConfigurationException {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.loadFromString(Files.readString(GUI));
        ConfigurationSection pot = configuration.getConfigurationSection("cooking-pot-gui");
        assertNotNull(pot, "gui.yml has no cooking-pot-gui section");
        return pot;
    }
}
