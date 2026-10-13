package com.huidu.farmersdelight;

import com.google.gson.JsonParser;
import com.huidu.farmersdelight.api.util.DatapackSupport;
import io.papermc.paper.datapack.DatapackRegistrar;
import io.papermc.paper.datapack.DiscoveredDatapack;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class FarmersDelightBootstrapTest {
    @TempDir Path directory;

    @Test
    void migratesDimensionCopiesBeforeTheActiveWorldPack() throws Exception {
        Path worldRoot = directory.resolve("world");
        Path dimension = worldRoot.resolve("dimensions/minecraft/overworld");
        Files.createDirectories(dimension);
        Files.writeString(worldRoot.resolve("level.dat"), "test");
        for (Path folder : List.of(worldRoot, dimension)) {
            Path legacy = folder.resolve("datapacks/test_pack/data/test/damage_type/burn.json");
            Files.createDirectories(legacy.getParent());
            Files.writeString(legacy, folder == worldRoot ? "active" : "ignored");
        }
        World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, args) -> method.getName().equals("getWorldFolder") ? dimension.toFile() : null);
        Path data = directory.resolve("plugin");
        DatapackSupport.migrateLegacyPack(world, data, "test_pack");
        assertEquals("active", Files.readString(data.resolve("datapacks/test_pack/data/test/damage_type/burn.json")));
        assertFalse(Files.exists(worldRoot.resolve("datapacks/test_pack")));
        assertFalse(Files.exists(dimension.resolve("datapacks/test_pack")));
        try (var backups = Files.list(data.resolve("datapack-backups"))) {
            assertEquals(2, backups.count());
        }
        assertTrue(Files.exists(data.resolve("datapacks/test_pack/.legacy-migrated")));
    }

    @Test
    void metadataMatchesOfficialReleaseFormats() {
        for (String entry : List.of("1.21.5=71", "1.21.6=80", "1.21.7=81", "1.21.8=81")) {
            String[] parts = entry.split("=");
            var pack = JsonParser.parseString(DatapackSupport.renderPackMetadata("test", parts[0]))
                    .getAsJsonObject().getAsJsonObject("pack");
            assertEquals(Integer.parseInt(parts[1]), pack.get("pack_format").getAsInt(), parts[0]);
        }
        for (String version : List.of("1.21.9", "1.21.10", "1.21.11", "26.1", "26.1.1", "26.1.2", "26.2")) {
            var pack = JsonParser.parseString(DatapackSupport.renderPackMetadata("test", version))
                    .getAsJsonObject().getAsJsonObject("pack");
            assertEquals(88, pack.get("min_format").getAsInt(), version);
            assertEquals(150, pack.get("max_format").getAsInt(), version);
        }
    }

    @Test
    void firstInstallLoadsAllDefinitionsAndDistributionTagsWithoutBukkit() throws Exception {
        Discovery discovery = new Discovery();
        FarmersDelightBootstrap.prepare(directory, "1.21.5", discovery.registrar());
        assertEquals(Set.of("farmersdelight_tags", "farmersdelight_enchant", "farmersdelight_damage"), discovery.added);
        Path enchants = directory.resolve("datapacks/farmersdelight_enchant");
        assertTrue(Files.isRegularFile(enchants.resolve("data/farmersdelight/enchantment/backstabbing.json")));
        assertTrue(Files.readString(enchants.resolve("data/minecraft/tags/enchantment/on_random_loot.json"))
                .contains("farmersdelight:backstabbing"));
        Path damage = directory.resolve("datapacks/farmersdelight_damage");
        assertTrue(Files.readString(damage.resolve("data/minecraft/tags/damage_type/is_fire.json"))
                .contains("farmersdelight:stove_burn"));
        assertEquals(71, JsonParser.parseString(Files.readString(damage.resolve("pack.mcmeta")))
                .getAsJsonObject().getAsJsonObject("pack").get("pack_format").getAsInt());
        assertEquals(0, discovery.autoEnabled);
        assertFalse(Files.exists(directory.resolve("config.yml")));
        assertFalse(Files.exists(directory.resolve("common-tags.yml")));
    }

    @Test
    void disabledPacksRemoveLegacyDiscoveryAndRetainManagedFiles() throws Exception {
        Files.writeString(directory.resolve("config.yml"), "datapacks:\n  tags-enabled: false\nenchantments:\n  install-datapack: false\ndamage-type:\n  install-datapack: false\n");
        Path custom = directory.resolve("datapacks/farmersdelight_enchant/data/custom/enchantment/example.json");
        Files.createDirectories(custom.getParent());
        Files.writeString(custom, "kept");
        Discovery discovery = new Discovery();
        FarmersDelightBootstrap.prepare(directory, "26.2", discovery.registrar());
        assertTrue(discovery.added.isEmpty());
        assertEquals(Set.of("file/farmersdelight_tags", "file/farmersdelight_enchant", "file/farmersdelight_damage"),
                Set.copyOf(discovery.removed));
        assertEquals("kept", Files.readString(custom));
    }

    @Test
    void cachedAddonReferencesExpandAgainstCurrentBuiltinTags() throws Exception {
        Files.writeString(directory.resolve("common-tags.yml"), "tags:\n  'c:root': [minecraft:carrot]\n");
        Path sources = directory.resolve("datapacks/tag-sources.yml");
        Files.createDirectories(sources.getParent());
        Files.writeString(sources, "tags:\n  'addon:vegetables': ['#c:root', minecraft:rabbit]\n");
        FarmersDelightBootstrap.prepare(directory, "26.2", new Discovery().registrar());
        Path tag = directory.resolve("datapacks/farmersdelight_tags/data/addon/tags/item/vegetables.json");
        String first = Files.readString(tag);
        assertTrue(first.contains("minecraft:carrot"));
        assertTrue(first.contains("minecraft:rabbit"));
        Files.writeString(directory.resolve("common-tags.yml"), "tags:\n  'c:root': [minecraft:potato]\n");
        FarmersDelightBootstrap.prepare(directory, "26.2", new Discovery().registrar());
        String second = Files.readString(tag);
        assertFalse(second.contains("minecraft:carrot"));
        assertTrue(second.contains("minecraft:potato"));
        assertTrue(second.contains("minecraft:rabbit"));
    }

    @Test
    void malformedConfigDoesNotFallBackAndOverwriteManagedData() throws Exception {
        Files.writeString(directory.resolve("config.yml"), "enchantments: [broken\n");
        Path preserved = directory.resolve("datapacks/farmersdelight_enchant/pack.mcmeta");
        Files.createDirectories(preserved.getParent());
        Files.writeString(preserved, "original");
        assertThrows(Exception.class, () -> FarmersDelightBootstrap.prepare(directory, "26.2", new Discovery().registrar()));
        assertEquals("original", Files.readString(preserved));
    }

    @Test
    void legacyMigrationKeepsCustomDefinitionsAndAnUntouchedBackup() throws Exception {
        Path world = directory.resolve("world");
        Path data = directory.resolve("plugin");
        Path legacy = world.resolve("datapacks/example/data/example/damage_type/custom.json");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "custom legacy definition");
        Path managed = data.resolve("datapacks/example/data/example/damage_type/custom.json");
        Files.createDirectories(managed.getParent());
        Files.writeString(managed, "bootstrap default");
        Path metadata = data.resolve("datapacks/example/pack.mcmeta");
        Files.writeString(metadata, "current metadata");
        Files.writeString(world.resolve("datapacks/example/pack.mcmeta"), "old metadata");
        DatapackSupport.migrateLegacyPack(world, data, "example");
        assertEquals("custom legacy definition", Files.readString(managed));
        assertEquals("current metadata", Files.readString(metadata));
        assertEquals("custom legacy definition", Files.readString(data.resolve("datapack-backups/example/data/example/damage_type/custom.json")));
        assertEquals("old metadata", Files.readString(data.resolve("datapack-backups/example/pack.mcmeta")));
        assertFalse(Files.exists(world.resolve("datapacks/example")));
        assertTrue(Files.exists(data.resolve("datapacks/example/.legacy-migrated")));
    }

    @Test
    void migrationBootUsesLegacyPackUntilItsFilesHaveBeenPreserved() throws Exception {
        Path pack = directory.resolve("datapacks/example");
        Files.createDirectories(pack);
        Files.writeString(pack.resolve("pack.mcmeta"), "metadata");
        Discovery first = new Discovery();
        first.legacy.add("file/example");
        DatapackSupport.discoverPack(first.registrar(), directory, "example", true);
        assertTrue(first.added.isEmpty());
        assertTrue(first.removed.isEmpty());
        Files.writeString(pack.resolve(".legacy-migrated"), "done");
        Discovery second = new Discovery();
        second.legacy.add("file/example");
        DatapackSupport.discoverPack(second.registrar(), directory, "example", true);
        assertEquals(Set.of("example"), second.added);
        assertEquals(List.of("file/example"), second.removed);
    }

    @Test
    void changedBackstabIdRetainsCachedAddonDefinitionsAndDistribution() throws Exception {
        FarmersDelightBootstrap.prepare(directory, "26.2", new Discovery().registrar());
        Path pack = directory.resolve("datapacks/farmersdelight_enchant");
        Path original = pack.resolve("data/farmersdelight/enchantment/backstabbing.json");
        Path addon = pack.resolve("data/addon/enchantment/custom.json");
        Files.createDirectories(addon.getParent());
        Files.copy(original, addon);
        String addonContent = Files.readString(addon);
        for (String tag : List.of("tradeable", "treasure", "on_random_loot")) {
            Files.writeString(pack.resolve("data/minecraft/tags/enchantment/" + tag + ".json"),
                    "{\"replace\":false,\"values\":[\"farmersdelight:backstabbing\",\"addon:custom\"]}");
        }
        Files.writeString(directory.resolve("config.yml"), "enchantments:\n  backstabbing:\n    id: farmersdelight:ambush\n");
        FarmersDelightBootstrap.prepare(directory, "26.2", new Discovery().registrar());
        assertFalse(Files.exists(original));
        assertEquals(addonContent, Files.readString(addon));
        assertTrue(Files.isRegularFile(pack.resolve("data/farmersdelight/enchantment/ambush.json")));
        String distribution = Files.readString(pack.resolve("data/minecraft/tags/enchantment/on_random_loot.json"));
        assertFalse(distribution.contains("farmersdelight:backstabbing"));
        assertTrue(distribution.contains("addon:custom"));
        assertTrue(distribution.contains("farmersdelight:ambush"));
    }

    @Test
    void tagIdCannotWriteOutsideItsManagedNamespace() throws Exception {
        Files.writeString(directory.resolve("common-tags.yml"), "tags:\n  'c:../../escape': [minecraft:carrot]\n");
        assertThrows(IllegalArgumentException.class,
                () -> FarmersDelightBootstrap.prepare(directory, "26.2", new Discovery().registrar()));
        assertFalse(Files.exists(directory.resolve("datapacks/escape.json")));
    }

    static final class Discovery {
        final Set<String> added = new HashSet<>();
        final List<String> removed = new ArrayList<>();
        final Set<String> legacy = new HashSet<>();
        int autoEnabled;

        DatapackRegistrar registrar() {
            return (DatapackRegistrar) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DatapackRegistrar.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("removeDiscoveredPack")) {
                            removed.add((String) args[0]);
                            return legacy.remove(args[0]);
                        }
                        if (method.getName().equals("hasPackDiscovered")) return legacy.contains(args[0]);
                        if (method.getName().equals("discoverPack")) {
                            added.add((String) args[1]);
                            DatapackRegistrar.Configurer options = (DatapackRegistrar.Configurer) Proxy.newProxyInstance(
                                    getClass().getClassLoader(), new Class<?>[]{DatapackRegistrar.Configurer.class},
                                    (config, setting, values) -> {
                                        if (setting.getName().equals("autoEnableOnServerStart") && Boolean.TRUE.equals(values[0])) autoEnabled++;
                                        return config;
                                    });
                            @SuppressWarnings("unchecked")
                            Consumer<DatapackRegistrar.Configurer> configure = (Consumer<DatapackRegistrar.Configurer>) args[2];
                            configure.accept(options);
                            return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DiscoveredDatapack.class},
                                    (result, accessor, values) -> accessor.getName().equals("getName") ? args[1] : null);
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
