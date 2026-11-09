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
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class FarmersDelightBootstrap implements PluginBootstrap {
    @Override
    public void bootstrap(BootstrapContext context) {
        context.getLifecycleManager().registerEventHandler(LifecycleEvents.DATAPACK_DISCOVERY, event -> {
            try {
                prepare(context.getDataDirectory(), ServerBuildInfo.buildInfo().minecraftVersionId(), event.registrar());
            } catch (Exception failure) {
                // Registry data is prepared before the plugin enables, so throwing here prevents the whole
                // server from starting. Report the exact failure and let the server boot; onEnable validates
                // the same files again and disables only this plugin if they are still unusable.
                System.getLogger(FarmersDelightBootstrap.class.getName()).log(System.Logger.Level.ERROR,
                        "Could not prepare FarmersDelight registry data; the server will start without "
                                + "the managed datapacks: " + failure.getMessage(), failure);
            }
        });
    }

    static void prepare(Path directory, String version, DatapackRegistrar registrar) throws Exception {
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
            } catch (Exception tagFailure) {
                reportPackFailure("tags", tagFailure);
            }
        }
        if (enchantsEnabled) {
            try {
                FarmersDelightDatapacks.prepareEnchantments(
                        DatapackSupport.managedPack(directory, FarmersDelightDatapacks.ENCHANT_PACK),
                        EnchantmentSettings.load(config.getConfigurationSection("enchantments")), version);
                enchantsPrepared = true;
            } catch (Exception enchantFailure) {
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
            } catch (Exception damageFailure) {
                reportPackFailure("damage types", damageFailure);
            }
        }
        // One discover failure must not stop the other two packs from being discovered.
        try {
            DatapackSupport.discoverPack(registrar, directory, FarmersDelightDatapacks.TAG_PACK, tagsPrepared);
        } catch (Exception discoverFailure) {
            reportPackFailure("tags (discovery)", discoverFailure);
        }
        try {
            DatapackSupport.discoverPack(registrar, directory, FarmersDelightDatapacks.ENCHANT_PACK, enchantsPrepared);
        } catch (Exception discoverFailure) {
            reportPackFailure("enchantments (discovery)", discoverFailure);
        }
        try {
            DatapackSupport.discoverPack(registrar, directory, FarmersDelightDatapacks.DAMAGE_PACK, damagePrepared);
        } catch (Exception discoverFailure) {
            reportPackFailure("damage types (discovery)", discoverFailure);
        }
    }

    private static void reportPackFailure(String pack, Exception failure) {
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
