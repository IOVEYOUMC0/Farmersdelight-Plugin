package com.huidu.farmersdelight.gui;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeEditorGuiConfigTest {

    private static final String YAML = """
            recipe-editor-gui:
              title: "Editor"
              rows: 1
              layout:
                - "IIXXXXXSX"
              legend:
                I: ingredient
                S: save
                X: background
            recipe-editor-cooking-pot-guis:
              large_pot:
                title: "Large"
                rows: 1
                layout:
                  - "IIIIXXXSX"
                legend:
                  I: ingredient
                  S: save
                  X: background
            """;

    @Test
    void parsesDefaultAndCustomPotLayouts() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(new StringReader(YAML));

        RecipeEditorGuiConfig config = RecipeEditorGuiConfig.fromConfig(yaml);

        RecipeViewGuiConfig.BaseConfig def = config.getCookingPotConfig(null);
        assertNotNull(def);
        assertEquals(2, def.getSlotsByType("ingredient").size());
        assertTrue(def.getFirstSlotByType("save") >= 0);

        RecipeViewGuiConfig.BaseConfig large = config.getCookingPotConfig("large_pot");
        assertNotNull(large);
        assertEquals(4, large.getSlotsByType("ingredient").size());
    }

    @Test
    void missingCustomPotFallsBackToDefault() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(new StringReader(YAML));

        RecipeEditorGuiConfig config = RecipeEditorGuiConfig.fromConfig(yaml);
        assertSame(config.getCookingPotConfig(null), config.getCookingPotConfig("does_not_exist"));
    }

    @Test
    void nullRootHasNoDefaultConfig() {
        RecipeEditorGuiConfig config = RecipeEditorGuiConfig.fromConfig(null);
        assertNull(config.getCookingPotConfig(null));
    }
}
