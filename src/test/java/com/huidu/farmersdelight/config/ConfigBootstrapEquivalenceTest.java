package com.huidu.farmersdelight.config;

import com.huidu.farmersdelight.api.config.ConfigFileUpdater;
import com.huidu.farmersdelight.api.config.ConfigKeyRename;
import com.huidu.farmersdelight.api.config.ConfigUpdatePolicy;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ConfigBootstrapEquivalenceTest {

    private static final String[][] LEGACY_MIGRATIONS = {
            {"knife-drops", "mob-extra-drops"},
            {"entity-extra-drops", "mob-extra-drops"},
            {"knife-drop-tools", "mob-extra-drop-tools"},
            {"entity-extra-drop-tools", "mob-extra-drop-tools"},
            {"knife-config", "drops.knife-items"},
            {"mob-extra-drop-tools", "drops.mob-extra-tools"},
            {"mob-extra-drops", "drops.mob-extra"},
            {"straw-drops", "drops.straw"},
            {"drops.knife-items", "knife-items"},
            {"cooking-pot.experience-reward", "experience-reward"},
            {"cooking-pot.container-returns", "container-returns"},
            {"recipe-discovery", "recipes.discovery"},
            {"buff-persistence", "buff.persistence"},
            {"bossbar", "buff.display"},
            {"comfort-foods", "buff.comfort"},
            {"nourishment-foods", "buff.nourishment"}
    };

    private static final List<String> LEGACY_RETIRED = List.of(
            "buff.comfort.fade-warning-ticks",
            "comfort-foods.fade-warning-ticks",
            "buff.nourishment.fade-warning-ticks",
            "nourishment-foods.fade-warning-ticks",
            "tray",
            "cooking-pot.tray",
            "handle",
            "cooking-pot.handle");

    private static final List<String> LEGACY_REGISTRY_SECTIONS = List.of(
            "heat-sources",
            "buff.comfort",
            "comfort-foods",
            "buff.nourishment",
            "nourishment-foods",
            "container-returns",
            "cooking-pot.container-returns",
            "cutting-board.display-overrides",
            "cutting-board.display-tag-overrides");

    @Test
    void policyCarriesTheSameTablesTheLegacyCodeHad() throws Exception {
        ConfigUpdatePolicy policy = livePolicy();

        String[][] migrations = new String[policy.migrations().size()][];
        for (int i = 0; i < migrations.length; i++) {
            ConfigKeyRename rename = policy.migrations().get(i);
            migrations[i] = new String[]{rename.oldPath(), rename.newPath()};
        }
        assertArrayEquals(LEGACY_MIGRATIONS, migrations, "migration table drifted");
        assertEquals(LEGACY_RETIRED, policy.retiredKeys(), "retired key table drifted");
        assertEquals(LEGACY_REGISTRY_SECTIONS, policy.registrySections(), "registry section table drifted");
    }

    @Test
    void freshConfigMatches() {
        assertSameResult(new YamlConfiguration());
    }

    @Test
    void untouchedCurrentConfigMatches() {
        assertSameResult(bundledConfig());
    }

    @Test
    void configUsingTheOldestKeyNamesMatches() {
        YamlConfiguration existing = new YamlConfiguration();
        existing.set("knife-drops.minecraft:cow", List.of("minecraft:leather"));
        existing.set("knife-drop-tools", List.of("minecraft:iron_sword"));
        existing.set("knife-config", List.of("farmersdelight:iron_knife"));
        existing.set("straw-drops.minecraft:wheat", 3);
        existing.set("cooking-pot.tray.enabled", false);
        existing.set("cooking-pot.handle.toggle-sound", "minecraft:block.note_block.harp");
        existing.set("cooking-pot.experience-reward", 7);
        existing.set("cooking-pot.container-returns.minecraft:bowl", "minecraft:bowl");
        existing.set("recipe-discovery.enabled", false);
        existing.set("bossbar.enabled", false);
        existing.set("comfort-foods.duration", 1234);
        existing.set("comfort-foods.fade-warning-ticks", 40);
        existing.set("nourishment-foods.fade-warning-ticks", 40);
        assertSameResult(existing);
    }

    @Test
    void configWithMigratedNamesAndRetiredKeysMatches() {
        YamlConfiguration existing = bundledConfig();
        existing.set("buff.comfort.fade-warning-ticks", 40);
        existing.set("buff.nourishment.fade-warning-ticks", 40);
        assertSameResult(existing);
    }

    @Test
    void configWithRegistryEntriesRemovedMatches() {
        YamlConfiguration existing = bundledConfig();
        // An operator who disabled content by deleting entries: the merge must not put any of them back.
        clearChildrenButOne(existing, "heat-sources");
        assertSameResult(existing);
    }

    @Test
    void configMissingWholeRegistrySectionsMatches() {
        YamlConfiguration existing = bundledConfig();
        // A section absent altogether is the pre-feature case: the merge fills it in completely.
        existing.set("heat-sources", null);
        assertSameResult(existing);
    }

    @Test
    void configMissingWholeFeatureBranchesMatches() {
        YamlConfiguration existing = bundledConfig();
        existing.set("cutting-board", null);
        existing.set("buff", null);
        assertSameResult(existing);
    }

    @Test
    void legacyGuiRecipeDetailIsCopiedOnlyToMissingSplitSections() {
        YamlConfiguration root = new YamlConfiguration();
        root.set("recipe-view-gui.recipe-detail.title", "custom");
        root.set("recipe-view-gui.recipe-detail.rows", 4);
        root.set("recipe-view-gui.recipe-detail-cooking-pot.title", "kept");

        ConfigurationSection recipeView = root.getConfigurationSection("recipe-view-gui");
        assertNotNull(recipeView);
        assertEquals(1, ConfigBootstrap.migrateLegacyGuiSections(recipeView));
        assertEquals("kept", recipeView.getString("recipe-detail-cooking-pot.title"));
        assertEquals("custom", recipeView.getString("recipe-detail-cutting-board.title"));
        assertEquals(4, recipeView.getInt("recipe-detail-cutting-board.rows"));
    }

    @Test
    void bundledWorldDataIsSeparateFromMainConfig() {
        assertFalse(bundledConfig().contains("world-data", true));
        Path path = Path.of("src", "main", "resources", "world-data.yml");
        YamlConfiguration worldData = YamlConfiguration.loadConfiguration(path.toFile());
        assertTrue(worldData.contains("trades.villager", true));
        assertTrue(worldData.contains("trades.wandering-trader", true));
    }

    @Test
    void bundledDropsAreSeparateFromMainConfig() {
        assertFalse(bundledConfig().contains("drops", true));
        Path path = Path.of("src", "main", "resources", "drops.yml");
        YamlConfiguration drops = YamlConfiguration.loadConfiguration(path.toFile());
        assertTrue(drops.contains("mob-extra-tools", true));
        assertTrue(drops.contains("mob-extra", true));
        assertTrue(drops.contains("straw", true));
    }

    @Test
    void legacyWorldDataReplacesTheBundledTradeLists() throws Exception {
        Path worldDataPath = Files.createTempFile("farmersdelight-world-data", ".yml");
        try {
            YamlConfiguration bundled = new YamlConfiguration();
            bundled.set("trades.villager.enabled", true);
            bundled.set("trades.wandering-trader.enabled", true);
            ConfigFileUpdater.tidy(bundled);
            Files.writeString(worldDataPath, bundled.saveToString(), StandardCharsets.UTF_8);

            YamlConfiguration legacy = new YamlConfiguration();
            legacy.set("trades.villager.enabled", false);
            ConfigBootstrap.copyLegacyWorldData(legacy, worldDataPath);

            YamlConfiguration migrated = YamlConfiguration.loadConfiguration(worldDataPath.toFile());
            assertFalse(migrated.getBoolean("trades.villager.enabled", true));
            assertFalse(migrated.contains("trades.wandering-trader", true));
        } finally {
            Files.deleteIfExists(worldDataPath);
        }
    }

    @Test
    void legacyDropsReplaceTheBundledDropGroups() throws Exception {
        Path dropsPath = Files.createTempFile("farmersdelight-drops", ".yml");
        try {
            YamlConfiguration bundled = new YamlConfiguration();
            bundled.set("mob-extra.pig.normal", "farmersdelight:ham");
            bundled.set("mob-extra.hoglin.normal", "farmersdelight:ham");
            bundled.set("mob-extra-tools.items", List.of("farmersdelight:iron_knife"));
            bundled.set("straw.mature_rice.drop", "farmersdelight:straw");
            ConfigFileUpdater.tidy(bundled);
            Files.writeString(dropsPath, bundled.saveToString(), StandardCharsets.UTF_8);

            YamlConfiguration legacy = new YamlConfiguration();
            legacy.set("mob-extra.pig.normal", "minecraft:porkchop");
            ConfigBootstrap.copyLegacyDrops(legacy, dropsPath);

            YamlConfiguration migrated = YamlConfiguration.loadConfiguration(dropsPath.toFile());
            assertEquals("minecraft:porkchop", migrated.getString("mob-extra.pig.normal"));
            assertFalse(migrated.contains("mob-extra.hoglin", true));
            assertFalse(migrated.contains("mob-extra-tools", true));
            assertFalse(migrated.contains("straw", true));
        } finally {
            Files.deleteIfExists(dropsPath);
        }
    }

    private void assertSameResult(YamlConfiguration input) {
        YamlConfiguration bundled = bundledConfig();

        YamlConfiguration legacy = copyOf(input);
        List<String[]> legacyMigrated = new ArrayList<>();
        for (String[] migration : LEGACY_MIGRATIONS) {
            if (legacyMigrateSection(legacy, migration[0], migration[1])) {
                legacyMigrated.add(migration);
            }
        }
        List<String> legacyRetired = new ArrayList<>();
        for (String path : LEGACY_RETIRED) {
            if (legacy.isSet(path)) {
                legacy.set(path, null);
                legacyRetired.add(path);
            }
        }
        int legacyAdded = legacyCopyMissingKeys(bundled, legacy);

        YamlConfiguration current = copyOf(input);
        List<ConfigKeyRename> currentMigrated =
                ConfigFileUpdater.applyMigrations(current, renames());
        List<String> currentRetired = ConfigFileUpdater.removeKeys(current, LEGACY_RETIRED);
        int currentAdded = ConfigFileUpdater.copyMissingKeys(bundledConfig(), current, LEGACY_REGISTRY_SECTIONS);

        assertEquals(legacyMigrated.size(), currentMigrated.size(), "number of applied renames differs");
        for (int i = 0; i < legacyMigrated.size(); i++) {
            assertEquals(legacyMigrated.get(i)[0], currentMigrated.get(i).oldPath(), "rename order differs");
            assertEquals(legacyMigrated.get(i)[1], currentMigrated.get(i).newPath(), "rename order differs");
        }
        assertEquals(legacyRetired, currentRetired, "retired paths differ");
        assertEquals(legacyAdded, currentAdded, "number of added settings differs");

        ConfigFileUpdater.tidy(legacy);
        ConfigFileUpdater.tidy(current);
        assertArrayEquals(legacy.saveToString().getBytes(StandardCharsets.UTF_8),
                current.saveToString().getBytes(StandardCharsets.UTF_8),
                "rewritten file bytes differ");
    }

    private static List<ConfigKeyRename> renames() {
        List<ConfigKeyRename> renames = new ArrayList<>();
        for (String[] entry : ConfigBootstrapEquivalenceTest.LEGACY_MIGRATIONS) {
            renames.add(new ConfigKeyRename(entry[0], entry[1]));
        }
        return renames;
    }

    private static ConfigUpdatePolicy livePolicy() throws Exception {
        Field field = ConfigBootstrap.class.getDeclaredField("CONFIG_POLICY");
        field.setAccessible(true);
        return (ConfigUpdatePolicy) field.get(null);
    }

    private static YamlConfiguration bundledConfig() {
        Path path = Path.of("src", "main", "resources", "config.yml");
        assertTrue(Files.exists(path), "bundled config.yml not found at " + path.toAbsolutePath());
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(path.toFile());
        assertFalse(loaded.getKeys(false).isEmpty(), "bundled config.yml loaded empty");
        return loaded;
    }

    private static YamlConfiguration copyOf(YamlConfiguration source) {
        YamlConfiguration copy = new YamlConfiguration();
        ConfigFileUpdater.tidy(source);
        try {
            copy.loadFromString(source.saveToString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return copy;
    }

    private static void clearChildrenButOne(YamlConfiguration configuration, String section) {
        ConfigurationSection body = configuration.getConfigurationSection(section);
        assertNotNull(body, "bundled config.yml has no section " + section);
        List<String> keys = new ArrayList<>(body.getKeys(false));
        assertTrue(keys.size() > 1, "section " + section + " has too few entries to test with");
        for (int i = 1; i < keys.size(); i++) {
            body.set(keys.get(i), null);
        }
    }

    // The algorithm as it stood in ConfigBootstrap before it moved to api/config.

    private static boolean legacyMigrateSection(YamlConfiguration config, String oldPath, String newPath) {
        if (config.isSet(newPath) || !config.isSet(oldPath)) {
            return false;
        }
        ConfigurationSection oldSection = config.getConfigurationSection(oldPath);
        if (oldSection != null) {
            ConfigurationSection newSection = config.createSection(newPath);
            legacyCopyConfigSection(oldSection, newSection);
        } else {
            config.set(newPath, config.get(oldPath));
        }
        config.set(oldPath, null);
        return true;
    }

    private static void legacyCopyConfigSection(ConfigurationSection source, ConfigurationSection target) {
        for (String key : source.getKeys(false)) {
            ConfigurationSection child = source.getConfigurationSection(key);
            if (child != null) {
                legacyCopyConfigSection(child, target.createSection(key));
            } else {
                target.set(key, source.get(key));
            }
        }
    }

    private static int legacyCopyMissingKeys(ConfigurationSection bundled, ConfigurationSection existing) {
        int added = 0;
        List<String> candidateSections = new ArrayList<>();
        Set<String> sectionsAdminAlreadyHad = new HashSet<>();
        for (String section : LEGACY_REGISTRY_SECTIONS) {
            if (existing.contains(section, true)) {
                sectionsAdminAlreadyHad.add(section);
            }
        }
        for (String key : bundled.getKeys(true)) {
            if (legacyIsSuppressedRegistryEntry(key, sectionsAdminAlreadyHad)) {
                continue;
            }
            if (bundled.isConfigurationSection(key)) {
                if (!existing.contains(key, true)) {
                    candidateSections.add(key);
                }
                continue;
            }
            if (existing.contains(key, true)) {
                continue;
            }
            Object value = bundled.get(key);
            if (value == null) {
                continue;
            }
            existing.set(key, value);
            legacyCopyComments(bundled, existing, key);
            added++;
        }
        for (String sectionKey : candidateSections) {
            if (existing.contains(sectionKey, true)) {
                legacyCopyComments(bundled, existing, sectionKey);
            }
        }
        return added;
    }

    private static boolean legacyIsSuppressedRegistryEntry(String key, Set<String> sectionsAdminAlreadyHad) {
        for (String section : LEGACY_REGISTRY_SECTIONS) {
            if (key.equals(section) || !key.startsWith(section + ".")) {
                continue;
            }
            if (sectionsAdminAlreadyHad.contains(section)) {
                return true;
            }
        }
        return false;
    }

    private static void legacyCopyComments(ConfigurationSection bundled, ConfigurationSection existing, String key) {
        List<String> comments = bundled.getComments(key);
        if (!comments.isEmpty()) {
            existing.setComments(key, comments);
        }
    }
}
