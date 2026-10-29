package com.huidu.farmersdelight.util;

import com.huidu.farmersdelight.api.config.ConfigSectionReader;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommonTagResolverTest {

    @Test
    void versionBumpsOnEveryRebuildSoDerivedCachesCanInvalidate() {
        long before = CommonTagResolver.version();
        String source = "test-version-source";
        try {
            CommonTagResolver.registerSource(source, Map.of("c:test/version", List.of("minecraft:stick")));
            long registered = CommonTagResolver.version();
            assertTrue(registered > before, "registerSource must invalidate derived tag caches");
            CommonTagResolver.unregisterSource(source);
            assertTrue(CommonTagResolver.version() > registered,
                    "unregisterSource must invalidate derived tag caches too");
        } finally {
            CommonTagResolver.unregisterSource(source);
        }
    }

    @Test
    void aCapturedGenerationKeepsBothIndexesConsistentAcrossRebuilds() {
        String source = "test-captured-tags";
        try {
            CommonTagResolver.registerSource(source, Map.of("test:snapshot", List.of("minecraft:stick")));
            CommonTagResolver.Snapshot old = CommonTagResolver.snapshot();
            CommonTagResolver.registerSource(source, Map.of("test:snapshot", List.of("minecraft:flint")));
            CommonTagResolver.Snapshot next = CommonTagResolver.snapshot();
            assertTrue(next.version() > old.version());
            assertEquals(Set.of("minecraft:stick"), old.tagToItems().get("test:snapshot"));
            assertTrue(old.itemToTags().get("minecraft:stick").contains("test:snapshot"));
            assertEquals(Set.of("minecraft:flint"), next.tagToItems().get("test:snapshot"));
            assertTrue(next.itemToTags().get("minecraft:flint").contains("test:snapshot"));
        } finally {
            CommonTagResolver.unregisterSource(source);
        }
    }

    @Test
    void mergesSourcesAndBuildsReverseIndex() {
        String source = "test-common-tags";
        String secondSource = "test-common-tags-2";
        try {
            CommonTagResolver.registerSource(source, Map.of(
                    "#C:Tools/Test", List.of("Minecraft:Stick", "Example:Tool"),
                    "c:tools/all", List.of("#c:tools/test")));
            CommonTagResolver.registerSource(secondSource,
                    Map.of("c:tools/test", List.of("minecraft:flint")));

            assertEquals(
                    Set.of("minecraft:stick", "example:tool", "minecraft:flint"),
                    CommonTagResolver.getMembers(Key.of("c:tools/test")));
            assertTrue(CommonTagResolver.getTagsForItemId(" MINECRAFT:STICK ")
                    .contains("c:tools/test"));
            assertEquals(
                    Set.of("minecraft:stick", "example:tool", "minecraft:flint"),
                    CommonTagResolver.getMembers("c:tools/all"));
        } finally {
            CommonTagResolver.unregisterSource(secondSource);
            CommonTagResolver.unregisterSource(source);
        }
    }

    @Test
    void bundledTagsConfigContainsOnlyStringLists() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getResourceAsStream("/common-tags.yml"), StandardCharsets.UTF_8));
        ConfigurationSection tags = config.getConfigurationSection("tags");
        assertTrue(tags != null && !tags.getKeys(false).isEmpty());
        for (String key : tags.getKeys(false)) {
            var members = ConfigSectionReader.optionalStringList(tags, key);
            assertTrue(!members.isEmpty(), key);
            assertTrue(members.stream().noneMatch(member -> member.startsWith("crabbersdelight:")
                    || member.startsWith("endsdelight:")), "Addon membership belongs in its CE pack: " + key);
        }
    }

    @Test
    void ignoresMissingAndCyclicReferencesWithoutBreakingOtherMembers() {
        String source = "test-common-tag-errors";
        try {
            CommonTagResolver.registerSource(source, Map.of(
                    "test:cycle_a", List.of("#test:cycle_b", "minecraft:stick"),
                    "test:cycle_b", List.of("#test:cycle_a"),
                    "test:missing", List.of("#test:not_registered")));

            assertEquals(Set.of("minecraft:stick"),
                    CommonTagResolver.getMembers("test:cycle_a"));
            assertTrue(CommonTagResolver.getMembers("test:missing").isEmpty());
        } finally {
            CommonTagResolver.unregisterSource(source);
        }
    }
}
