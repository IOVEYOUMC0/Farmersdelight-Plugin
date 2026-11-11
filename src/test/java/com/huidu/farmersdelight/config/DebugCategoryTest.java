package com.huidu.farmersdelight.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugCategoryTest {

    @Test
    void everyCategoryIdIsLowercaseAndUnique() {
        Set<String> ids = new java.util.LinkedHashSet<>();
        for (DebugCategory category : DebugCategory.values()) {
            String id = category.id();
            assertEquals(id.toLowerCase(java.util.Locale.ROOT), id, "category ids are lowercase config names");
            assertTrue(ids.add(id), "duplicate category id: " + id);
            assertEquals(category, DebugCategory.byId(id).orElseThrow());
        }
    }

    @Test
    void enumLookupMatchesAllAndConfiguredCategories() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("debug.enabled", true);
        yaml.set("debug.categories", List.of("gui", "skillet"));
        DebugSettings settings = DebugSettings.load(yaml);

        assertTrue(settings.enabledFor(DebugCategory.GUI));
        assertTrue(settings.enabledFor(DebugCategory.SKILLET));
        assertFalse(settings.enabledFor(DebugCategory.STARTUP));
        assertFalse(settings.enabledFor((DebugCategory) null));
    }

    @Test
    void allAndStarEnableEveryCategory() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("debug.enabled", true);
        yaml.set("debug.categories", List.of("*"));
        DebugSettings star = DebugSettings.load(yaml);
        for (DebugCategory category : DebugCategory.values()) {
            assertTrue(star.enabledFor(category), "star should enable " + category.id());
        }

        yaml.set("debug.categories", List.of("all"));
        DebugSettings all = DebugSettings.load(yaml);
        for (DebugCategory category : DebugCategory.values()) {
            assertTrue(all.enabledFor(category), "all should enable " + category.id());
        }
    }

    @Test
    void namesTheEnumDoesNotKnowAreReported() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("debug.enabled", true);
        yaml.set("debug.categories", List.of("gui", "gul", "ALL", "stove"));
        DebugSettings settings = DebugSettings.load(yaml);

        assertEquals(List.of("gul"), settings.unknownCategories(),
                "case-insensitive known ids are not unknown, and the wildcard is not reported");
    }

    @Test
    void disabledMasterSwitchWinsOverCategories() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("debug.enabled", false);
        yaml.set("debug.categories", List.of("gui"));
        assertFalse(DebugSettings.load(yaml).enabledFor(DebugCategory.GUI));
    }
}
