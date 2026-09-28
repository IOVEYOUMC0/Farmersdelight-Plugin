package com.huidu.farmersdelight.config;

import com.huidu.farmersdelight.api.config.ConfigFileUpdater;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the two behaviours the config manager relies on internally: a bundled YAML is parsed once per
 * resource path, and the missing-key merge short-circuits registry handling for files without registries.
 */
class ConfigResourcesAndMergeTest {

    /**
     * Minimal Plugin that only answers getResource, and counts the calls. A real
     * JavaPlugin cannot be instantiated in a unit test: its constructor needs a live server.
     */
    private static Plugin resourcePlugin(AtomicInteger reads) {
        InvocationHandler handler = (Object proxy, Method method, Object[] args) -> {
            if (method.getName().equals("getResource")) {
                reads.incrementAndGet();
                Path path = Path.of("src", "main", "resources", String.valueOf(args[0]));
                return Files.exists(path) ? Files.newInputStream(path) : null;
            }
            if (method.getName().equals("toString")) {
                return "resource-plugin";
            }
            throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                ConfigResourcesAndMergeTest.class.getClassLoader(), new Class<?>[]{Plugin.class}, handler);
    }

    @Test
    void bundledYamlIsParsedOncePerResourcePath() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        Plugin plugin = resourcePlugin(reads);

        YamlConfiguration first = ConfigResources.yaml(plugin, "config.yml");
        YamlConfiguration second = ConfigResources.yaml(plugin, "config.yml");

        assertNotNull(first);
        assertSame(first, second, "the second read must be served from the cache");
        assertEquals(1, reads.get(), "the resource must be read exactly once");
        assertNotNull(first.getConfigurationSection("performance.warnings"),
                "parsed content is the real bundled config.yml");
    }

    @Test
    void missingResourceIsReportedAsAbsentWithoutRereading() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        Plugin plugin = resourcePlugin(reads);

        assertNull(ConfigResources.yaml(plugin, "no-such-file.yml"));
        assertNull(ConfigResources.yaml(plugin, "no-such-file.yml"));
        assertEquals(1, reads.get(), "a missing resource is remembered too");
    }

    @Test
    void mergingWithoutRegistrySectionsAddsEveryMissingSetting() throws Exception {
        YamlConfiguration bundled = new YamlConfiguration();
        bundled.loadFromString("""
                section:
                  child: 1
                other: leaf
                """);

        YamlConfiguration existing = new YamlConfiguration();
        existing.loadFromString("keep: kept\n");

        int added = ConfigFileUpdater.copyMissingKeys(bundled, existing, List.of());

        // getKeys(true) reports a section before its children, and creating the section already creates the
        // child path, so the child is not counted a second time.
        assertEquals(2, added, "the section header and the scalar are added; the child rides along");
        assertEquals("kept", existing.getString("keep"), "an existing value is never replaced");
        assertTrue(existing.isConfigurationSection("section"));
        assertEquals(1, existing.getInt("section.child"));
        assertEquals("leaf", existing.getString("other"));

        assertEquals(0, ConfigFileUpdater.copyMissingKeys(bundled, existing, List.of()),
                "a second merge on the same shape adds nothing");
    }

    @Test
    void aDeletedRegistryEntryStaysDeletedButAWipedRegistrySectionIsRefilled() throws Exception {
        YamlConfiguration bundled = new YamlConfiguration();
        bundled.loadFromString("""
                items:
                  sword:
                    name: Sword
                """);

        // The operator kept the section and deleted the entry: deleting is how an entry is disabled, so the
        // merge must leave it deleted instead of writing the bundled entry back.
        YamlConfiguration pruned = new YamlConfiguration();
        pruned.createSection("items");
        assertEquals(0, ConfigFileUpdater.copyMissingKeys(bundled, pruned, List.of("items")));
        assertFalse(pruned.contains("items.sword", true), "a deleted registry entry must not come back");

        // The operator removed the whole section: there is no opt-out to protect, so it is filled in.
        YamlConfiguration wiped = new YamlConfiguration();
        assertTrue(ConfigFileUpdater.copyMissingKeys(bundled, wiped, List.of("items")) > 0);
        assertTrue(wiped.contains("items.sword.name", true));

        // A sibling section whose name merely shares the prefix is not an entry of the registry section.
        YamlConfiguration sibling = new YamlConfiguration();
        sibling.createSection("items-extra");
        assertTrue(ConfigFileUpdater.copyMissingKeys(bundled, sibling, List.of("items")) > 0,
                "items-extra must not suppress the entries of items");
        assertTrue(sibling.contains("items.sword.name", true));
    }
}
