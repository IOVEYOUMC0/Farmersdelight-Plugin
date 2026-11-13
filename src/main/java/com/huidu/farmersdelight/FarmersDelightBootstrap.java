package com.huidu.farmersdelight;

import com.huidu.farmersdelight.api.util.DatapackSupport;
import com.huidu.farmersdelight.api.util.DamageTypePack;
import com.huidu.farmersdelight.config.EnchantmentSettings;
import com.huidu.farmersdelight.datapack.FarmersDelightDatapacks;
import com.huidu.farmersdelight.util.CommonTagDefinitions;
import io.papermc.paper.ServerBuildInfo;
import io.papermc.paper.datapack.DatapackRegistrar;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class FarmersDelightBootstrap implements PluginBootstrap {
    private static final String DEFAULT_PLUGIN_NAME = "FarmersDelight";

    @Override
    public void bootstrap(BootstrapContext context) {
        String pluginName = context.getPluginMeta().getName();
        context.getLifecycleManager().registerEventHandler(LifecycleEvents.DATAPACK_DISCOVERY, event -> {
            try {
                Map<String, String> discovered = prepare(context.getDataDirectory(),
                        ServerBuildInfo.buildInfo().minecraftVersionId(), event.registrar(), pluginName);
                if (!discovered.isEmpty()) {
                    StringBuilder summary = new StringBuilder();
                    discovered.forEach((pack, id) -> {
                        if (!summary.isEmpty()) summary.append(", ");
                        summary.append(id);
                    });
                    context.getLogger().info("FarmersDelight datapacks discovered: " + summary);
                }
            } catch (Exception | LinkageError failure) {
                // Registry data is prepared before the plugin enables, so throwing here prevents the whole
                // server from starting. Report the exact failure and let the server boot; onEnable validates
                // the same files again and disables only this plugin if they are still unusable.
                System.getLogger(FarmersDelightBootstrap.class.getName()).log(System.Logger.Level.ERROR,
                        "Could not prepare FarmersDelight registry data; the server will start without "
                                + "the managed datapacks: " + failure.getMessage(), failure);
            }
        });
    }

    /** Test/compatibility overload: the bootstrap path always passes the real plugin name. */
    static Map<String, String> prepare(Path directory, String version, DatapackRegistrar registrar) throws Exception {
        return prepare(directory, version, registrar, DEFAULT_PLUGIN_NAME);
    }

    static Map<String, String> prepare(Path directory, String version, DatapackRegistrar registrar,
                                       String pluginName) throws Exception {
        YamlConfiguration config = DatapackSupport.startupConfiguration(directory.resolve("config.yml"),
                FarmersDelightBootstrap.class.getResourceAsStream("/config.yml"));
        boolean tagsEnabled = config.getBoolean("datapacks.tags-enabled", true);
        boolean enchantsEnabled = config.getBoolean("enchantments.install-datapack", true)
                && config.getBoolean("enchantments.enabled", true);
        boolean damageEnabled = config.getBoolean("damage-type.install-datapack", true);
        // Each pack is only discovered when this boot actually prepared it: a failed prepare must not leave the
        // previous run's pack.mcmeta to be picked up again.
        boolean tagsPrepared = false;
        boolean enchantsPrepared = false;
        boolean damagePrepared = false;
        Map<String, String> discovered = new LinkedHashMap<>();
        // Each pack is prepared on its own: a broken file in one of them used to abort this whole method, so
        // the other two packs were never written for that boot (and the failure surfaced as one ERROR line).
        if (tagsEnabled) {
            try {
                YamlConfiguration tags = DatapackSupport.startupConfiguration(directory.resolve("common-tags.yml"),
                        FarmersDelightBootstrap.class.getResourceAsStream("/common-tags.yml"));
                Map<String, Set<String>> raw = readTags(tags.getConfigurationSection("tags"));
                Path cache = directory.resolve("datapacks/tag-sources.yml");
                if (Files.isRegularFile(cache)) {
                    YamlConfiguration external = DatapackSupport.startupConfiguration(cache, null);
                    readTags(external.getConfigurationSection("tags")).forEach((tag, members) ->
                            raw.computeIfAbsent(tag, key -> new HashSet<>()).addAll(members));
                }
                Set<String> problems = new HashSet<>();
                FarmersDelightDatapacks.writeTags(
                        DatapackSupport.managedPack(directory, FarmersDelightDatapacks.TAG_PACK),
                        CommonTagDefinitions.expand(raw, problems), version);
                if (!problems.isEmpty()) {
                    // Runtime also reports these through the configured language once I18n is ready.
                    System.getLogger(FarmersDelightBootstrap.class.getName()).log(System.Logger.Level.WARNING,
                            "Invalid common tag references: " + problems);
                }
                tagsPrepared = true;
            } catch (Exception | LinkageError tagFailure) {
                reportPackFailure("tags", tagFailure);
            }
        }
        if (enchantsEnabled) {
            try {
                FarmersDelightDatapacks.prepareEnchantments(
                        DatapackSupport.managedPack(directory, FarmersDelightDatapacks.ENCHANT_PACK),
                        EnchantmentSettings.load(config.getConfigurationSection("enchantments")), version);
                enchantsPrepared = true;
            } catch (Exception | LinkageError enchantFailure) {
                reportPackFailure("enchantments", enchantFailure);
            }
        }
        if (damageEnabled) {
            try {
                DamageTypePack.prepare(directory.resolve("damage-types.yml"),
                        FarmersDelightBootstrap.class.getResourceAsStream("/damage-types.yml"),
                        DatapackSupport.managedPack(directory, FarmersDelightDatapacks.DAMAGE_PACK),
                        "FarmersDelight damage types", version);
                damagePrepared = true;
            } catch (Exception | LinkageError damageFailure) {
                reportPackFailure("damage types", damageFailure);
            }
        }
        // One discover failure must not stop the other two packs from being discovered.
        try {
            DatapackSupport.discoverPack(registrar, directory, FarmersDelightDatapacks.TAG_PACK, tagsPrepared);
            recordDiscovery(registrar, pluginName, FarmersDelightDatapacks.TAG_PACK, tagsPrepared, discovered);
        } catch (Exception | LinkageError discoverFailure) {
            reportPackFailure("tags (discovery)", discoverFailure);
        }
        try {
            DatapackSupport.discoverPack(registrar, directory, FarmersDelightDatapacks.ENCHANT_PACK, enchantsPrepared);
            recordDiscovery(registrar, pluginName, FarmersDelightDatapacks.ENCHANT_PACK, enchantsPrepared, discovered);
        } catch (Exception | LinkageError discoverFailure) {
            reportPackFailure("enchantments (discovery)", discoverFailure);
        }
        try {
            DatapackSupport.discoverPack(registrar, directory, FarmersDelightDatapacks.DAMAGE_PACK, damagePrepared);
            recordDiscovery(registrar, pluginName, FarmersDelightDatapacks.DAMAGE_PACK, damagePrepared, discovered);
        } catch (Exception | LinkageError discoverFailure) {
            reportPackFailure("damage types (discovery)", discoverFailure);
        }
        return discovered;
    }

    private static void recordDiscovery(DatapackRegistrar registrar, String pluginName, String name,
                                        boolean prepared, Map<String, String> discovered) {
        if (!prepared) {
            return;
        }
        String own = pluginName + "/" + name;
        if (registrar.hasPackDiscovered(own)) {
            discovered.put(name, own);
            return;
        }
        // The legacy world-folder pack is not yet migrated and stays visible for this boot.
        String legacy = "file/" + name;
        if (registrar.hasPackDiscovered(legacy)) {
            discovered.put(name, legacy);
        }
    }

    private static void reportPackFailure(String pack, Throwable failure) {
        System.getLogger(FarmersDelightBootstrap.class.getName()).log(System.Logger.Level.ERROR,
                "Could not prepare the FarmersDelight " + pack + " datapack; the other packs are unaffected: "
                        + failure.getMessage(), failure);
    }

    private static Map<String, Set<String>> readTags(ConfigurationSection section) {
        Map<String, Set<String>> raw = new HashMap<>();
        if (section == null) return raw;
        section.getValues(false).forEach((tag, value) -> {
            if (!(value instanceof List<?> entries)) {
                throw new IllegalArgumentException("Expected a list for common tag " + tag);
            }
            Set<String> members = new HashSet<>();
            for (Object entry : entries) {
                if (!(entry instanceof String member)) {
                    throw new IllegalArgumentException("Expected an item ID for common tag " + tag);
                }
                String normalized = CommonTagDefinitions.normalizeMember(member);
                if (!normalized.isEmpty()) members.add(normalized);
            }
            if (!members.isEmpty()) raw.put(CommonTagDefinitions.normalize(tag), members);
        });
        return raw;
    }
}
