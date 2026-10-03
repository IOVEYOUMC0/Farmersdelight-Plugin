package com.huidu.farmersdelight.config;

import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PetFoodConfigTest {

    @Test
    void parsesCraftEngineTemptSettings() {
        ConfigSection section = ConfigSection.ofRoot(Map.of(
                "enabled", true,
                "range", 12.5,
                "move-speed", 1.5,
                "tick-interval", 8,
                "ignore-owned-tamed", false
        ));

        PetFoodConfig.TemptSettings tempt = PetFoodConfig.parseTemptDefinition(section);

        assertTrue(tempt.enabled());
        Assertions.assertEquals(12.5D, tempt.range(), 0.0001D);
        Assertions.assertEquals(1.5D, tempt.moveSpeed(), 0.0001D);
        Assertions.assertEquals(8L, tempt.tickInterval());
        assertFalse(tempt.ignoreOwnedTamed());
    }

    @Test
    void rejectsCraftEnginePetFoodWithoutAValidEntity() {
        ConfigSection section = ConfigSection.ofRoot(Map.of("entities", List.of("not_an_entity")));

        assertNull(PetFoodConfig.parseFoodDefinition(section));
    }
}
